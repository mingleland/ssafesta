package com.example.ssafesta.wallet;

import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Daily coin grant (spec 003 FR-003, FR-003a).
 *
 * <p>"One day" is a {@code Asia/Seoul} calendar date, and that rule is expressed in the
 * idempotency key itself — {@code DAILY_GRANT:{userId}:{KST date}}. Because the key is unique in
 * the ledger, refreshes, re-logins and simultaneous requests can only ever produce one grant per
 * member per KST date; there is no separate "last granted" state that could drift out of sync.
 *
 * <p>Redis is a cache in front of that rule, never the rule itself: a hit skips the database, a
 * miss or a Redis outage falls through to the database path where correctness still holds.
 */
@Service
public class DailyCoinGrantService {

    private static final Logger log = LoggerFactory.getLogger(DailyCoinGrantService.class);
    private static final Duration MIN_CACHE_TTL = Duration.ofMinutes(1);

    private final WalletService wallets;
    private final StringRedisTemplate redis;
    private final WalletProperties properties;
    private final String keyspace;

    public DailyCoinGrantService(WalletService wallets, StringRedisTemplate redis, WalletProperties properties,
            RedisKeyspaceProperties keyspace) {
        this.wallets = wallets;
        this.redis = redis;
        this.properties = properties;
        this.keyspace = keyspace.prefix();
    }

    /**
     * Grants today's coins if the member has not received them yet.
     *
     * @return {@code true} when this call recorded the grant
     */
    public boolean grantIfDue(Long userId) {
        return grantIfDue(userId, currentDate());
    }

    /**
     * Grants the coins for an explicit KST date. Exists so the date boundary can be exercised in
     * tests without waiting for midnight; production callers use {@link #grantIfDue(Long)}.
     */
    public boolean grantIfDue(Long userId, LocalDate today) {
        if (properties.dailyGrant() <= 0) {
            return false;
        }
        if (isCachedAsGranted(userId, today)) {
            return false;
        }

        LedgerResult result = wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.CHARGE,
                properties.dailyGrant(), CoinReason.DAILY_GRANT, null, null, dailyGrantKey(userId, today)));
        cacheAsGranted(userId, today);

        if (result.alreadyApplied()) {
            log.debug("일일 코인이 이미 지급되어 있습니다 — userId={}, date={}", userId, today);
            return false;
        }
        log.info("일일 코인 지급 — userId={}, date={}, amount={}, balance={}",
                userId, today, properties.dailyGrant(), result.balanceAfter());
        return true;
    }

    /** Today's date in the grant time zone (KST), which decides which day a grant belongs to. */
    public LocalDate currentDate() {
        return LocalDate.now(properties.dailyGrantZone());
    }

    public static String dailyGrantKey(Long userId, LocalDate date) {
        return CoinReason.DAILY_GRANT + ":" + userId + ":" + date;
    }

    private boolean isCachedAsGranted(Long userId, LocalDate date) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(cacheKey(userId, date)));
        } catch (DataAccessException exception) {
            // The cache only saves a query. Correctness lives in the ledger's unique constraint,
            // so a Redis outage degrades performance, not accuracy — but it must not stay silent.
            log.warn("일일 지급 캐시 조회 실패 — DB 경로로 진행합니다. userId={}, date={}", userId, date, exception);
            return false;
        }
    }

    private void cacheAsGranted(Long userId, LocalDate date) {
        try {
            redis.opsForValue().set(cacheKey(userId, date), "1", ttlUntilNextMidnight(date));
        } catch (DataAccessException exception) {
            log.warn("일일 지급 캐시 기록 실패 — 다음 요청은 DB를 조회합니다. userId={}, date={}", userId, date, exception);
        }
    }

    private String cacheKey(Long userId, LocalDate date) {
        return keyspace + "wallet:daily:" + userId + ":" + date;
    }

    private Duration ttlUntilNextMidnight(LocalDate date) {
        ZoneId zone = properties.dailyGrantZone();
        ZonedDateTime nextMidnight = date.plusDays(1).atStartOfDay(zone);
        Duration remaining = Duration.between(ZonedDateTime.now(zone), nextMidnight);
        return remaining.compareTo(MIN_CACHE_TTL) < 0 ? MIN_CACHE_TTL : remaining;
    }
}
