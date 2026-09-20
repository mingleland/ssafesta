package com.example.ssafesta.mission;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import com.example.ssafesta.wallet.WalletProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Records the one day-scoped mission fact that has no existing durable source: successful world-session issuance.
 *
 * <p>This is deliberately not the daily-grant cache. That cache is set by every authenticated API
 * request, while this key is written only after a member's world-entry grant is successfully
 * issued. The claim remains durable in the coin ledger; this key only answers today's completion.
 */
@Service
public class WorldMissionProgressService {

    private final StringRedisTemplate redis;
    private final WalletProperties walletProperties;
    private final String keyspace;

    public WorldMissionProgressService(StringRedisTemplate redis, WalletProperties walletProperties,
                                       RedisKeyspaceProperties keyspace) {
        this.redis = redis;
        this.walletProperties = walletProperties;
        this.keyspace = keyspace.prefix();
    }

    /** Writes the KST-day completion flag before the caller returns the world-entry grant. */
    public void markEntered(Long userId) {
        LocalDate date = today();
        try {
            redis.opsForValue().setIfAbsent(key(userId, date), "1", ttlUntilNextMidnight(date));
        } catch (DataAccessException exception) {
            throw new ApiException(ErrorCode.WORLD_MISSION_UNAVAILABLE);
        }
    }

    /** Whether this member has received at least one world-entry grant on the specified KST date. */
    public boolean hasEntered(Long userId, LocalDate date) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(userId, date)));
        } catch (DataAccessException exception) {
            throw new ApiException(ErrorCode.WORLD_MISSION_UNAVAILABLE);
        }
    }

    private LocalDate today() {
        return LocalDate.now(walletProperties.dailyGrantZone());
    }

    private String key(Long userId, LocalDate date) {
        return keyspace + "mission:world-enter:" + userId + ":" + date;
    }

    private Duration ttlUntilNextMidnight(LocalDate date) {
        ZonedDateTime now = ZonedDateTime.now(walletProperties.dailyGrantZone());
        return Duration.between(now, date.plusDays(1).atStartOfDay(walletProperties.dailyGrantZone()));
    }
}
