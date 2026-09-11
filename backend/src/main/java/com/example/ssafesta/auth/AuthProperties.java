package com.example.ssafesta.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtClockSkew how long past its {@code exp} a token is still accepted. Spring's default is
 *                     60 seconds and it applies whether or not anyone writes it down — so it is
 *                     written down here, because a second reader needs the same number:
 *                     {@code SurveyGuestKeySweeper} must not delete a duplicate-guard key while the
 *                     token holding it can still be used (헌법 12조 vs 1인 1응답).
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                             Duration oauthStateTtl, Duration jwtClockSkew, String refreshCookiePath,
                             String frontendBaseUrl, boolean cookieSecure) {

    public AuthProperties {
        // 음수면 아직 유효한 토큰의 중복 방지 키를 지우게 된다 — 조용히 넘기면 게스트 재응답이
        // 열리고, 그 시점에는 원인이 이 설정이라는 것을 알 방법이 없다.
        if (jwtClockSkew != null && jwtClockSkew.isNegative()) {
            throw new IllegalStateException("app.auth.jwt-clock-skew 은 음수일 수 없습니다: " + jwtClockSkew);
        }
    }
}
