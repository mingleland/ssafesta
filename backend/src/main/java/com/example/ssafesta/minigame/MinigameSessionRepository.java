package com.example.ssafesta.minigame;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MinigameSessionRepository extends JpaRepository<MinigameSession, Long> {

    long countByUserIdAndGameTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            Long userId, String gameType, java.time.Instant from, java.time.Instant to);

    long countByUserIdAndGameTypeAndStatusAndRewardCoinGreaterThanAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
            Long userId, String gameType, MinigameSessionStatus status, int rewardCoin,
            java.time.Instant from, java.time.Instant to);

    /**
     * The only lookup this feature needs. No {@code FOR UPDATE}: submissions for one session are
     * by construction submissions by one member, and the wallet row lock taken before this call
     * already serializes them ({@code TimerStopService.submit}).
     */
    Optional<MinigameSession> findByNonce(UUID nonce);
}
