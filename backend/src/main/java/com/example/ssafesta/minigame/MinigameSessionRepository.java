package com.example.ssafesta.minigame;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface MinigameSessionRepository extends JpaRepository<MinigameSession, Long> {

    long countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            Long userId, String gameType, java.time.Instant from, java.time.Instant to);

    long countByUserIdAndGameTypeAndStatusAndRewardCoinGreaterThanAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
            Long userId, String gameType, MinigameSessionStatus status, int rewardCoin,
            java.time.Instant from, java.time.Instant to);

    long countByUserIdAndGameTypeAndStatusAndResultValueGreaterThanEqualAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
            Long userId, String gameType, MinigameSessionStatus status, BigDecimal resultValue,
            Instant from, Instant to);

    @Query("""
            select max(s.resultValue) from MinigameSession s
            where s.userId = :userId and s.gameType = :gameType
              and s.startedAt >= :from and s.startedAt < :to
            """)
    BigDecimal findTopScoreByUserIdAndGameTypeAndStartedAtBetween(
            @Param("userId") Long userId, @Param("gameType") String gameType,
            @Param("from") Instant from, @Param("to") Instant to);

    Optional<MinigameSession> findFirstByUserIdAndGameTypeOrderByStartedAtDesc(Long userId, String gameType);

    /**
     * The only lookup this feature needs. No {@code FOR UPDATE}: submissions for one session are
     * by construction submissions by one member, and the wallet row lock taken before this call
     * already serializes them ({@code TimerStopService.submit}).
     */
    Optional<MinigameSession> findByNonce(UUID nonce);
}
