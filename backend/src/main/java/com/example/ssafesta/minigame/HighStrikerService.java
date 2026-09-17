package com.example.ssafesta.minigame;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records server-approved world high-striker swings as the durable facts used by daily missions.
 *
 * <p>The Netcode server remains the authority that animates a swing and rolls its displayed score.
 * This service deliberately does not move that real-time path into Spring: it validates and stores
 * the owner's completed result afterwards. A wallet-row lock serializes reports from one member,
 * which makes the cooldown effective even when two HTTP requests arrive together.
 */
@Service
public class HighStrikerService {

    public static final String GAME_TYPE = "HIGH_STRIKER";
    public static final BigDecimal MISSION_SCORE = BigDecimal.valueOf(400);
    private static final int MAX_SCORE = 999;
    private static final Duration MIN_PLAY_INTERVAL = Duration.ofMillis(3200);
    private static final Set<String> MACHINE_IDS = Set.of("plaza-high-striker-01");

    private final MinigameSessionRepository sessions;
    private final WalletService wallets;

    public HighStrikerService(MinigameSessionRepository sessions, WalletService wallets) {
        this.sessions = sessions;
        this.wallets = wallets;
    }

    @Transactional
    public PlayRecorded record(Long userId, PlayCommand command) {
        String machineId = command == null ? null : command.machineId();
        Integer submittedScore = command == null ? null : command.score();
        if (machineId == null || machineId.isBlank()) {
            throw ApiException.fieldInvalid("machineId", "machineId는 필수입니다.");
        }
        if (!MACHINE_IDS.contains(machineId)) {
            throw new ApiException(ErrorCode.HIGH_STRIKER_NOT_FOUND);
        }
        if (submittedScore == null || submittedScore < 1) {
            throw ApiException.fieldInvalid("score", "score는 1 이상이어야 합니다.");
        }

        wallets.lockOwner(userId);
        Instant now = Instant.now();
        sessions.findFirstByUserIdAndGameTypeOrderByStartedAtDesc(userId, GAME_TYPE)
                .filter(previous -> previous.getStartedAt().plus(MIN_PLAY_INTERVAL).isAfter(now))
                .ifPresent(previous -> {
                    throw new ApiException(ErrorCode.HIGH_STRIKER_TOO_FAST);
                });

        int score = Math.min(submittedScore, MAX_SCORE);
        BigDecimal value = BigDecimal.valueOf(score);
        MinigameSession play = MinigameSession.start(userId, GAME_TYPE, UUID.randomUUID(), value, now);
        play.complete(value, 0, now);
        sessions.save(play);

        Instant todayStart = todayStart(now);
        Instant tomorrowStart = tomorrowStart(now);
        long playsToday = sessions.countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                userId, GAME_TYPE, todayStart, tomorrowStart);
        BigDecimal best = sessions.findTopScoreByUserIdAndGameTypeAndStartedAtBetween(
                userId, GAME_TYPE, todayStart, tomorrowStart);
        return new PlayRecorded(play.getNonce(), score, playsToday, best == null ? score : best.intValue());
    }

    private Instant todayStart(Instant now) {
        return now.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }

    private Instant tomorrowStart(Instant now) {
        return now.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }

    public record PlayCommand(String machineId, Integer score) { }

    public record PlayRecorded(UUID playId, int score, long playsToday, int bestScoreToday) { }
}
