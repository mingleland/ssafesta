package com.example.ssafesta.booth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lease pricing and duration (spec 004 D02, BE brief "100 Coin/1일").
 *
 * <p>Externalised because 기획 owns these numbers. The server is the only source of them: a lease
 * request never carries a price or an end time (헌법 16조).
 */
@ConfigurationProperties("app.lease")
public record LeaseProperties(int priceCoin, Duration duration) {

    public LeaseProperties {
        if (priceCoin < 0) {
            throw new IllegalArgumentException("임대 가격은 음수일 수 없습니다.");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("임대 기간은 0보다 커야 합니다.");
        }
    }
}
