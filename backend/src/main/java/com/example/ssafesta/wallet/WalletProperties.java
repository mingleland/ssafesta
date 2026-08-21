package com.example.ssafesta.wallet;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coin grant policy (spec 003 C-01, C-02).
 *
 * <p>{@code dailyGrantZone} decides which calendar day a daily grant belongs to. It is business
 * policy, not a display preference: the spec defines "one day" as an {@code Asia/Seoul} midnight
 * boundary while timestamps stay UTC.
 */
@ConfigurationProperties("app.wallet")
public record WalletProperties(int initialGrant, int dailyGrant, ZoneId dailyGrantZone) {

    public WalletProperties {
        if (initialGrant < 0 || dailyGrant < 0) {
            throw new IllegalArgumentException("코인 지급량은 음수일 수 없습니다.");
        }
        if (dailyGrantZone == null) {
            throw new IllegalArgumentException("일일 지급 기준 시간대가 설정되지 않았습니다.");
        }
    }
}
