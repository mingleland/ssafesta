package com.example.ssafesta.minigame;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The timing-stop minigame (spec 014). Since 2026-09-16 it is one of two — the plaza slot machine
 * is the other ({@link SlotMachineService}, spec 021) — and the two share only the wallet.
 *
 * <p><b>The server owns the verdict.</b> It issues the target time, keeps its own start instant,
 * computes the error, picks the reward band, writes the ledger and applies the daily cap. The only
 * thing read from the client is {@code stoppedSeconds} (C-06, FR-008, 헌법 12·16조).
 *
 * <p><b>What the elapsed-time check does and does not do.</b> The reported stop time is required to
 * agree with the server's own elapsed time to within a tolerance, in <i>both</i> directions: too
 * high means a stop that has not happened yet, too low means the answer was held back and sent
 * late. That closes the obvious replay, but {@code stoppedSeconds} is still client input — someone
 * who actually waits ~7s and then posts {@code 7.000} passes. This is delay and gross-mismatch
 * detection, not anti-cheat. Full server authority would have to measure by the arrival instant of
 * the stop, and then the ±0.1s band would be decided by round-trip time instead of by the player,
 * which is exactly why GitLab #134 §2 settled on this shape.
 *
 * <p><b>Failures are not HTTP errors.</b> A refused claim, a timeout, a day already at its cap and
 * a resubmission all answer 200 — a client distinguishes them by the body. Acceptance Scenario 4
 * says a capped member still gets to play, so 429 never appears here.
 */
@Service
public class TimerStopService {

    /** {@code minigame_sessions.game_type}. Stored uppercase; the URL spells it {@code timer-stop}. */
    static final String GAME_TYPE = "TIMER_STOP";

    /** {@code coin_ledger_entries.reference_type} for a minigame reward. */
    static final String REWARD_REFERENCE_TYPE = "MINIGAME_SESSION";

    /** Seconds are {@code NUMERIC(8,3)}; anything larger would overflow the column as a 500. */
    private static final BigDecimal MAX_SECONDS = new BigDecimal("99999.999");

    private static final int SECONDS_SCALE = 3;

    private final MinigameSessionRepository sessions;
    private final WalletService wallets;
    private final MinigameProperties properties;

    public TimerStopService(MinigameSessionRepository sessions, WalletService wallets,
                            MinigameProperties properties) {
        this.sessions = sessions;
        this.wallets = wallets;
        this.properties = properties;
    }

    /**
     * Issues a session with a server-chosen target time (FR-001a).
     *
     * <p>No cap check here: a member who has already earned the day's maximum may still play, they
     * simply earn nothing (Acceptance Scenario 4).
     */
    @Transactional
    public SessionIssued issue(Long userId) {
        MinigameProperties.TimerStop config = properties.timerStop();
        Instant now = Instant.now();
        MinigameSession session = sessions.save(MinigameSession.start(
                userId, GAME_TYPE, UUID.randomUUID(), randomTarget(config), now));
        return new SessionIssued(session.getNonce(), session.getTargetValue(),
                config.failAfterSeconds(session.getTargetValue()), session.getStartedAt());
    }

    /**
     * Judges one play and settles the reward.
     *
     * <p>The order below is the whole correctness argument:
     *
     * <ol>
     *   <li><b>Wallet lock first, session second.</b> Two submissions of one session are by
     *       construction two submissions by one member, so the wallet row lock serializes them and
     *       the session row needs no {@code FOR UPDATE} of its own. The loser blocks in
     *       {@code lockOwner} and only then issues its own {@code findByNonce} — a fresh statement
     *       under READ COMMITTED, so it sees the winner's committed {@code COMPLETED} and replays.
     *       Read the session before the lock and both threads judge a pre-lock snapshot and pay.
     *   <li><b>The daily total is summed inside that lock</b> for the same reason: two plays at
     *       48/50 that both read 48 both grant, and the day totals 58 (SC-003).
     *   <li><b>The ledger write is last</b>, and its idempotency key is derived from the session, so
     *       a double payment is refused even if the lock argument above were ever wrong:
     *       {@code WalletService.applyEntry} looks the key up under its own wallet lock and returns
     *       the entry already recorded. The {@code UNIQUE} index behind it is the backstop to that
     *       lookup, not the thing that normally fires.
     * </ol>
     *
     * <p>No flush is forced before crediting. {@code SurveyResponseService} has to, because a
     * duplicate-submission {@code INSERT} would abort the transaction and leave nothing able to run
     * after it; here the session row already exists and is only updated.
     */
    @Transactional
    public SubmitResult submit(Long userId, UUID sessionId, SubmitCommand command) {
        BigDecimal stopped = validatedStopSeconds(command);

        wallets.lockOwner(userId);
        MinigameSession session = sessions.findByNonce(sessionId)
                .filter(row -> row.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(ErrorCode.MINIGAME_SESSION_NOT_FOUND));

        if (session.isTerminal()) {
            return replay(userId, session);
        }

        MinigameProperties.TimerStop config = properties.timerStop();
        Instant now = Instant.now();
        BigDecimal target = session.getTargetValue();
        BigDecimal serverElapsed = elapsedSeconds(session.getStartedAt(), now);

        if (serverElapsed.subtract(stopped).abs().compareTo(config.elapsedToleranceSeconds()) > 0) {
            session.reject(stopped, now);
            Daily daily = dailyOf(userId, 0);
            return new SubmitResult(false, errorSeconds(target, stopped), 0, false, 0,
                    daily.limitReached(), daily.remaining(), "Result rejected");
        }

        BigDecimal error = errorSeconds(target, stopped);
        boolean timedOut = stopped.compareTo(config.failAfterSeconds(target)) > 0;
        int tier = timedOut ? 0 : config.tierOf(error);

        Daily daily = dailyOf(userId, config.coinsOfTier(tier));
        session.complete(stopped, daily.granted(), now);
        if (daily.granted() > 0) {
            LedgerResult ledger = wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD,
                    daily.granted(), CoinReason.MINIGAME_REWARD, REWARD_REFERENCE_TYPE,
                    session.getNonce().toString(), rewardKey(session.getNonce())));
            session.linkReward(ledger.entryId());
        }
        return new SubmitResult(true, error, tier, timedOut, daily.granted(),
                daily.limitReached(), daily.remaining(), message(tier, timedOut, daily.granted()));
    }

    /** Idempotency key for one session's reward — stable, and never a timestamp. */
    public static String rewardKey(UUID sessionId) {
        return CoinReason.MINIGAME_REWARD + ":" + REWARD_REFERENCE_TYPE + ":" + sessionId;
    }

    /**
     * A resubmission returns the first verdict, not an error — FR-004 forbids paying twice, not
     * answering twice, and a client retrying after a timeout needs this to look like success.
     *
     * <p>{@code accepted}, {@code rewardedCoins} and {@code errorSeconds} come from the stored row.
     * {@code dailyRemainingCoins} and {@code dailyLimitReached} are deliberately <b>not</b> — they
     * answer "can I earn more right now", so they are recomputed. A first play that reported 45
     * remaining will report less once later plays have earned, and that is the useful answer.
     *
     * <p>{@code tier}, {@code timedOut} and {@code message} are re-derived from the current
     * configuration rather than stored, so editing the tier table can make a later replay of an
     * older session report a band that no longer matches its {@code rewardedCoins}. Sessions are
     * settled in seconds and the table is a balance knob, so no column is kept to freeze them.
     */
    private SubmitResult replay(Long userId, MinigameSession session) {
        MinigameProperties.TimerStop config = properties.timerStop();
        boolean accepted = session.getStatus() == MinigameSessionStatus.COMPLETED;
        BigDecimal stopped = session.getResultValue();
        BigDecimal error = errorSeconds(session.getTargetValue(), stopped);
        boolean timedOut = accepted
                && stopped.compareTo(config.failAfterSeconds(session.getTargetValue())) > 0;
        int tier = accepted && !timedOut ? config.tierOf(error) : 0;
        Daily daily = dailyOf(userId, 0);
        return new SubmitResult(accepted, error, tier, timedOut, session.getRewardCoin(),
                daily.limitReached(), daily.remaining(),
                accepted ? message(tier, timedOut, session.getRewardCoin()) : "Result rejected");
    }

    /**
     * What today's cap leaves for a reward of {@code tierCoins}.
     *
     * <p>Clipped rather than refused: the cap is "일일 최대 50 Coin", so a member at 48 earning a
     * 5-coin round takes 2. {@code limitReached} then means "nothing left to earn", which is the
     * question the HUD is asking — it is true at 45 + 5 as well as at 50 + 0.
     */
    private Daily dailyOf(Long userId, int tierCoins) {
        int cap = properties.dailyCapCoins();
        int today = wallets.grantedTodayFor(userId, CoinReason.MINIGAME_REWARD);
        int granted = Math.max(0, Math.min(tierCoins, cap - today));
        int remaining = Math.max(0, cap - (today + granted));
        return new Daily(granted, remaining, remaining == 0);
    }

    private record Daily(int granted, int remaining, boolean limitReached) {
    }

    /**
     * ASCII only, on purpose. No client renders this today — the FE overlay builds its own Korean
     * copy from the verdict fields (S15P21A604-601), which is the right way round. This line stays
     * for logs and for reading a raw response, so it keeps the ASCII constraint rather than
     * growing a second, divergent source of player-facing wording.
     */
    private String message(int tier, boolean timedOut, int granted) {
        if (timedOut) {
            return "Too slow";
        }
        if (granted > 0) {
            return "+" + granted + " coins (tier " + tier + ")";
        }
        // Earned a band and still got nothing: the cap took it. Derived from the verdict rather
        // than from today's running total, so a replay says the same thing it said the first time.
        if (tier > 0) {
            return "Daily limit reached";
        }
        return "No reward";
    }

    private BigDecimal randomTarget(MinigameProperties.TimerStop config) {
        // Drawn in milliseconds so the value is exact in NUMERIC(8,3) from the start — a double
        // rounded to scale 3 afterwards is not the number that was drawn. Bounds come from
        // configuration; hard-coding 5..10 here would make the yml keys decorative.
        long minMillis = config.targetMinSeconds().movePointRight(SECONDS_SCALE).longValueExact();
        long maxMillis = config.targetMaxSeconds().movePointRight(SECONDS_SCALE).longValueExact();
        return BigDecimal.valueOf(ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1),
                SECONDS_SCALE);
    }

    private BigDecimal validatedStopSeconds(SubmitCommand command) {
        if (command == null || command.stoppedSeconds() == null) {
            throw ApiException.fieldInvalid("stoppedSeconds", "정지 시각(stoppedSeconds)이 필요합니다.");
        }
        // Rounded before the range check, not after: rounding 99999.9995 up would push it over the
        // column's ceiling on the way out.
        BigDecimal stopped = command.stoppedSeconds().setScale(SECONDS_SCALE, RoundingMode.HALF_UP);
        if (stopped.signum() < 0 || stopped.compareTo(MAX_SECONDS) > 0) {
            throw ApiException.fieldInvalid("stoppedSeconds",
                    "정지 시각은 0 이상 " + MAX_SECONDS + " 이하여야 합니다: " + command.stoppedSeconds());
        }
        return stopped;
    }

    private BigDecimal elapsedSeconds(Instant from, Instant to) {
        return BigDecimal.valueOf(Duration.between(from, to).toMillis(), SECONDS_SCALE);
    }

    private BigDecimal errorSeconds(BigDecimal target, BigDecimal stopped) {
        return target.subtract(stopped).abs();
    }

    /**
     * @param sessionId       the handle for the result call — server-issued (FR-004)
     * @param targetSeconds   the time to stop on
     * @param failAfterSeconds past this the round is a failure (FR-001d)
     * @param serverStartedAt the instant the server started counting; the client's own timer starts
     *                        later by one network hop, and that difference is what the tolerance covers
     */
    public record SessionIssued(
            @Schema(description = "결과 제출에 쓰는 세션 식별자", format = "uuid") UUID sessionId,
            @Schema(description = "맞춰야 할 목표 시간(초). 서버가 매 판 무작위로 발급한다", example = "7.381")
            BigDecimal targetSeconds,
            @Schema(description = "이 시간을 넘기면 실패로 끝난다", example = "10.381")
            BigDecimal failAfterSeconds,
            @Schema(description = "서버가 계측을 시작한 시각(UTC)") Instant serverStartedAt) {
    }

    /**
     * @param stoppedSeconds how long after the start the player stopped. The only field read — a
     *                       client that also sends {@code targetSeconds}, {@code errorSeconds} or
     *                       {@code timedOut} has them ignored rather than refused
     */
    @Schema(name = "TimerStopSubmitCommand")
    public record SubmitCommand(
            @Schema(description = "플레이어가 정지시킨 시각(초, 시작 기준 경과)", example = "7.41")
            BigDecimal stoppedSeconds) {
    }

    /**
     * @param accepted            false only when the claim failed the elapsed-time check (FR-008)
     * @param errorSeconds        |목표 − 정지|, computed by the server
     * @param tier                0 = 무보상, larger is better
     * @param timedOut            the round ran past {@code failAfterSeconds}
     * @param rewardedCoins       what was actually granted, after the daily clip
     * @param dailyLimitReached   {@code dailyRemainingCoins == 0} — "더 받을 수 없다", not "이번 판이
     *                            잘렸다". Kept alongside the number so a client can branch on the
     *                            state without deriving it
     * @param dailyRemainingCoins what is left of today's cap after this grant
     * @param message             short ASCII line describing the verdict; for logs, not for display
     *                            (the FE overlay writes its own copy from the fields above)
     */
    @Schema(name = "TimerStopSubmitResult")
    public record SubmitResult(boolean accepted, BigDecimal errorSeconds, int tier, boolean timedOut,
                               int rewardedCoins, boolean dailyLimitReached, int dailyRemainingCoins,
                               String message) {
    }
}
