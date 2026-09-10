package com.example.ssafesta.minigame;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.minigame.TimerStopService.SessionIssued;
import com.example.ssafesta.minigame.TimerStopService.SubmitCommand;
import com.example.ssafesta.minigame.TimerStopService.SubmitResult;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Reward settlement, the daily cap and resubmission — spec 014 FR-004, FR-006, C-04 (T014, T015).
 *
 * <p><b>Why the timer is compressed here.</b> Earning a reward means reporting a stop time that
 * agrees with the server's own elapsed time, and at the shipped 5~10s targets that would mean a
 * test literally waiting seven seconds per case. The target range and the failure margin are cut
 * down instead. The <b>elapsed tolerance is left at its production value</b> — it is the one number
 * with a security meaning, and a test that relaxes it proves nothing about the shipped system.
 *
 * <p>Called at the service level rather than through MockMvc: a race or a cap breach should fail
 * here as the exception or the number it actually is, not as a status code
 * ({@code SurveyResponseConcurrencyIntegrationTest} makes the same choice).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "app.minigame.timer-stop.target-min-seconds=0.001",
        "app.minigame.timer-stop.target-max-seconds=0.002",
        "app.minigame.timer-stop.fail-margin-seconds=1.0"})
class MinigameRewardIntegrationTest {

    @Autowired private TimerStopService timerStop;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private MinigameSessionRepository sessions;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private MinigameProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aPerfectStopEarnsTheNarrowestBandAndLandsInTheLedger() {
        Long userId = member("미니정답");
        SessionIssued issued = timerStop.issue(userId);
        int balanceBefore = wallets.balanceOf(userId);

        SubmitResult result = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.targetSeconds()));

        assertTrue(result.accepted());
        assertFalse(result.timedOut());
        assertEquals(2, result.tier(), "오차 0 은 가장 좁은 구간이어야 합니다.");
        assertEquals(5, result.rewardedCoins());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.errorSeconds()));
        assertEquals(balanceBefore + 5, wallets.balanceOf(userId));
        assertEquals(1, ledgerRowsFor(issued.sessionId()));
        assertNotNull(sessions.findByNonce(issued.sessionId()).orElseThrow().getRewardLedgerEntryId());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aWiderMissEarnsTheWiderBand() {
        Long userId = member("미니근사");
        SessionIssued issued = timerStop.issue(userId);

        SubmitResult result = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.targetSeconds().add(new BigDecimal("0.300"))));

        assertEquals(1, result.tier());
        assertEquals(2, result.rewardedCoins());
    }

    @Test
    void missingEveryBandPaysNothingButStillCounts() {
        Long userId = member("미니빗나감");
        SessionIssued issued = timerStop.issue(userId);
        int balanceBefore = wallets.balanceOf(userId);

        SubmitResult result = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.targetSeconds().add(new BigDecimal("0.800"))));

        assertTrue(result.accepted(), "구간을 못 맞춘 것은 정상 플레이입니다.");
        assertFalse(result.timedOut());
        assertEquals(0, result.tier());
        assertEquals(0, result.rewardedCoins());
        assertEquals(balanceBefore, wallets.balanceOf(userId));
        assertEquals(MinigameSessionStatus.COMPLETED,
                sessions.findByNonce(issued.sessionId()).orElseThrow().getStatus());
    }

    @Test
    void runningPastTheFailureThresholdIsAcceptedAndPaysNothing() {
        Long userId = member("미니시간초과");
        SessionIssued issued = timerStop.issue(userId);
        int balanceBefore = wallets.balanceOf(userId);

        SubmitResult result = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.failAfterSeconds().add(new BigDecimal("0.200"))));

        assertTrue(result.accepted(), "실패는 판정 거부가 아닙니다 — 200 이고 accepted 입니다.");
        assertTrue(result.timedOut());
        assertEquals(0, result.tier());
        assertEquals(0, result.rewardedCoins());
        assertEquals(balanceBefore, wallets.balanceOf(userId));
    }

    @Test
    void resubmittingReturnsTheFirstVerdictAndPaysNothingMore() {
        // FR-004. A client that retried after a network timeout must see success, not a 4xx, and
        // must not be paid twice.
        Long userId = member("미니재제출");
        SessionIssued issued = timerStop.issue(userId);
        SubmitResult first = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.targetSeconds()));
        int balanceAfterFirst = wallets.balanceOf(userId);

        SubmitResult second = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.targetSeconds().add(new BigDecimal("0.400"))));

        assertEquals(first.accepted(), second.accepted());
        assertEquals(first.tier(), second.tier(), "두 번째 제출값이 판정을 바꾸면 안 됩니다.");
        assertEquals(first.rewardedCoins(), second.rewardedCoins());
        assertEquals(first.timedOut(), second.timedOut());
        assertEquals(0, first.errorSeconds().compareTo(second.errorSeconds()));
        assertEquals(balanceAfterFirst, wallets.balanceOf(userId));
        assertEquals(1, ledgerRowsFor(issued.sessionId()), "원장 행은 한 개여야 합니다.");
        assertBalanceMatchesLedger(wallets, userId);
    }

    // ── 일일 한도 (C-04) ────────────────────────────────────────────────────

    @Test
    void aRewardThatFitsUnderTheCapIsPaidInFull() {
        SubmitResult result = playAfterEarning("미니한도20", 20);

        assertEquals(5, result.rewardedCoins());
        assertEquals(25, result.dailyRemainingCoins());
        assertFalse(result.dailyLimitReached());
    }

    @Test
    void aRewardThatExactlyFillsTheCapIsStillPaidInFullAndReportsTheLimit() {
        // dailyLimitReached asks "can I earn more", not "was this round clipped" — so it is true
        // here even though nothing was taken away.
        SubmitResult result = playAfterEarning("미니한도45", 45);

        assertEquals(5, result.rewardedCoins());
        assertEquals(0, result.dailyRemainingCoins());
        assertTrue(result.dailyLimitReached());
    }

    @Test
    void aRewardLargerThanWhatIsLeftIsClippedRatherThanRefused() {
        SubmitResult result = playAfterEarning("미니한도48", 48);

        assertEquals(2, result.rewardedCoins(), "남은 한도만큼만 지급되어야 합니다.");
        assertEquals(0, result.dailyRemainingCoins());
        assertTrue(result.dailyLimitReached());
    }

    @Test
    void aCappedMemberStillPlaysAndIsToldWhyNothingCame() {
        // Acceptance Scenario 4 — the round is valid, the reward is zero, and the client learns it
        // from the body rather than from a 429.
        SubmitResult result = playAfterEarning("미니한도50", properties.dailyCapCoins());

        assertTrue(result.accepted());
        assertEquals(2, result.tier(), "한도에 걸려도 판정 자체는 정상입니다.");
        assertEquals(0, result.rewardedCoins());
        assertEquals(0, result.dailyRemainingCoins());
        assertTrue(result.dailyLimitReached());
        assertEquals("Daily limit reached", result.message());
    }

    @Test
    void theCapCountsOnlyMinigameRewardsFromTheKoreanCalendarDay() {
        // The subtle piece: "today" is an Asia/Seoul day mapped onto a half-open instant range.
        // An entry written at 23:59:59.999 KST belongs to that day and one at 00:00:00.000 to the
        // next; a closed range would count the boundary twice.
        Long userId = member("미니자정");
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate day = LocalDate.now(zone).minusDays(30);

        creditMinigame(userId, 7, "boundary-late");
        backdate(userId, day.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1));
        creditMinigame(userId, 11, "boundary-next");
        backdate(userId, day.plusDays(1).atStartOfDay(zone).toInstant());

        assertEquals(7, wallets.grantedOnDateFor(userId, CoinReason.MINIGAME_REWARD, day));
        assertEquals(11, wallets.grantedOnDateFor(userId, CoinReason.MINIGAME_REWARD, day.plusDays(1)));
        assertEquals(0, wallets.grantedOnDateFor(userId, CoinReason.MINIGAME_REWARD, day.minusDays(1)));
        // 다른 사유의 지급은 미니게임 한도를 잠식하지 않는다 — 가입 지급이 원장에 이미 들어 있다.
        assertTrue(wallets.balanceOf(userId) > 18);
    }

    private SubmitResult playAfterEarning(String prefix, int alreadyEarnedToday) {
        Long userId = member(prefix);
        if (alreadyEarnedToday > 0) {
            creditMinigame(userId, alreadyEarnedToday, "seed");
        }
        SessionIssued issued = timerStop.issue(userId);
        SubmitResult result = timerStop.submit(userId, issued.sessionId(),
                new SubmitCommand(issued.targetSeconds()));
        assertBalanceMatchesLedger(wallets, userId);
        return result;
    }

    private void creditMinigame(Long userId, int coins, String tag) {
        String reference = tag + "-" + UUID.randomUUID();
        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, coins,
                CoinReason.MINIGAME_REWARD, TimerStopService.REWARD_REFERENCE_TYPE, reference,
                CoinReason.MINIGAME_REWARD + ":" + TimerStopService.REWARD_REFERENCE_TYPE + ":" + reference));
    }

    /** {@code created_at} has no setter — the column is append-only — so the seed is moved in SQL. */
    private void backdate(Long userId, Instant createdAt) {
        jdbc.update("""
                update coin_ledger_entries set created_at = ?
                 where id = (select max(e.id) from coin_ledger_entries e
                              join wallets w on w.id = e.wallet_id where w.user_id = ?)
                """, java.sql.Timestamp.from(createdAt), userId);
    }

    private long ledgerRowsFor(UUID sessionId) {
        return ledger.findByIdempotencyKey(TimerStopService.rewardKey(sessionId)).isPresent() ? 1 : 0;
    }

    private Long member(String prefix) {
        Long userId = createMember(users, prefix);
        wallets.openWallet(userId);
        return userId;
    }
}
