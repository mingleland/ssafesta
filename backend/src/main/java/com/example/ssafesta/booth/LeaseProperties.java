package com.example.ssafesta.booth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lease pricing and duration (spec 004 D02, BE brief "50 Coin/1일").
 *
 * <p>Externalised because 기획 owns these numbers. The server is the only source of them: a lease
 * request never carries a price or an end time (헌법 16조).
 *
 * <p>{@code adminEndsAt} is the one that is not a 기획 number but a schema decision
 * (S15P21A604-905): an administrator's booth is free and never expires, and "never" is written as a
 * far-future instant rather than {@code NULL} so that {@code status = ACTIVE AND ends_at > now}
 * keeps working unchanged in the sweeper, the expiry warning and every read.
 */
@ConfigurationProperties("app.lease")
public record LeaseProperties(int priceCoin, Duration duration, Instant adminEndsAt) {

    /**
     * A typo like {@code 2026-12-31} would silently give administrators an ordinary expiring lease,
     * and nothing downstream would complain — the booth would simply vanish one day. Refuse to start
     * instead.
     */
    private static final Instant FAR_FUTURE_FLOOR = Instant.parse("2090-01-01T00:00:00Z");

    public LeaseProperties {
        if (priceCoin < 0) {
            throw new IllegalArgumentException("임대 가격은 음수일 수 없습니다.");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("임대 기간은 0보다 커야 합니다.");
        }
        if (adminEndsAt == null || adminEndsAt.isBefore(FAR_FUTURE_FLOOR)) {
            throw new IllegalArgumentException(
                    "관리자 임대 만료 시각은 " + FAR_FUTURE_FLOOR + " 이후여야 합니다. 현재 값: " + adminEndsAt);
        }
    }
}
