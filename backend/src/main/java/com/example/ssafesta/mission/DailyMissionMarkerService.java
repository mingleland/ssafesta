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
 * Records the day-scoped mission facts that have no existing durable source.
 *
 * <p>Two of the nine missions are like this. {@code WORLD_ENTER} happens in the game server, whose
 * state is not durable, so Spring writes the marker after it issues the world-entry grant.
 * {@code AI_CONSULT} happens in FastAPI, which serves the conversation without passing through
 * Spring at all, so FastAPI reports it over the service-token path and Spring writes the marker
 * there (spec 022 FR-003a, S15P21A604-955). The other seven read existing tables.
 *
 * <p>This is deliberately not the daily-grant cache. That cache is set by every authenticated API
 * request, while these keys are written only after the fact they stand for actually happened. The
 * claim remains durable in the coin ledger; these keys only answer today's completion.
 *
 * <p><b>The key's mission segment is a slug, not {@code name()}.</b> The world key was written
 * before this class was general and is asserted verbatim by {@code WorldSessionApiIntegrationTest};
 * deriving the segment from the enum constant would silently change a key that is already in Redis
 * with a day-long TTL, so a deploy would lose every marker written before it.
 */
@Service
public class DailyMissionMarkerService {

    private final StringRedisTemplate redis;
    private final WalletProperties walletProperties;
    private final String keyspace;

    public DailyMissionMarkerService(StringRedisTemplate redis, WalletProperties walletProperties,
                                     RedisKeyspaceProperties keyspace) {
        this.redis = redis;
        this.walletProperties = walletProperties;
        this.keyspace = keyspace.prefix();
    }

    /**
     * Writes the KST-day completion flag for one mission.
     *
     * <p>Failure handling belongs to the caller, and the two callers differ on purpose (FR-004a):
     * world entry refuses the grant rather than hand out an entry it cannot count, while the AI
     * conversation is the point of the feature and a lost marker must not cost the member the
     * conversation itself.
     */
    public void mark(DailyMission mission, Long userId) {
        LocalDate date = today();
        try {
            redis.opsForValue().setIfAbsent(key(mission, userId, date), "1", ttlUntilNextMidnight(date));
        } catch (DataAccessException exception) {
            throw new ApiException(ErrorCode.MISSION_PROGRESS_UNAVAILABLE);
        }
    }

    /** Whether this member's marker for that mission exists on the specified KST date. */
    public boolean has(DailyMission mission, Long userId, LocalDate date) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(mission, userId, date)));
        } catch (DataAccessException exception) {
            throw new ApiException(ErrorCode.MISSION_PROGRESS_UNAVAILABLE);
        }
    }

    private LocalDate today() {
        return LocalDate.now(walletProperties.dailyGrantZone());
    }

    private String key(DailyMission mission, Long userId, LocalDate date) {
        return keyspace + "mission:" + slug(mission) + ":" + userId + ":" + date;
    }

    private static String slug(DailyMission mission) {
        return switch (mission) {
            case WORLD_ENTER -> "world-enter";
            case AI_CONSULT -> "ai-consult";
            default -> throw new IllegalArgumentException(
                    mission + " 은 마커로 판정하는 미션이 아닙니다 — 기존 사실 기록에서 계산합니다.");
        };
    }

    private Duration ttlUntilNextMidnight(LocalDate date) {
        ZonedDateTime now = ZonedDateTime.now(walletProperties.dailyGrantZone());
        return Duration.between(now, date.plusDays(1).atStartOfDay(walletProperties.dailyGrantZone()));
    }
}
