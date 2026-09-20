package com.example.ssafesta.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtClockSkew how long past its {@code exp} a token is still accepted. Spring's default is
 *                     60 seconds and it applies whether or not anyone writes it down — so it is
 *                     written down here, because a second reader needs the same number:
 *                     {@code SurveyGuestKeySweeper} must not delete a duplicate-guard key while the
 *                     token holding it can still be used (헌법 12조 vs 1인 1응답).
 * @param refreshReuseGrace how long after a rotation the token it replaced may be replayed without
 *                     the whole family being revoked. A second browser tab bootstrapping a moment
 *                     later is not a stolen token, and treating it as one logs both tabs out
 *                     (S15P21A604-764, GitLab #198). Outside this window the replay is still a
 *                     replay and spec 001 시나리오 7 applies unchanged.
 * @param sessionRotationGrace how long the {@code sid} a rotation replaced still passes
 *                     {@code MemberSessionService.check}. React and Unity share one Access Token,
 *                     so without this window one side's ordinary refresh kills the token the other
 *                     side is holding (GitLab #216). Deliberately <b>not</b> the same property as
 *                     {@code refreshReuseGrace}: that one decides whether replaying a refresh token
 *                     counts as theft, this one decides how long two Access Tokens may overlap.
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                             Duration oauthStateTtl, Duration jwtClockSkew, Duration refreshReuseGrace,
                             Duration sessionRotationGrace, String refreshCookiePath, String frontendBaseUrl,
                             boolean cookieSecure) {

    public AuthProperties {
        // 음수면 아직 유효한 토큰의 중복 방지 키를 지우게 된다 — 조용히 넘기면 게스트 재응답이
        // 열리고, 그 시점에는 원인이 이 설정이라는 것을 알 방법이 없다.
        if (jwtClockSkew != null && jwtClockSkew.isNegative()) {
            throw new IllegalStateException("app.auth.jwt-clock-skew 은 음수일 수 없습니다: " + jwtClockSkew);
        }
        // 0 이하면 유예가 없는 것과 같은데, 설정에는 값이 있어 보인다. 그 상태로 배포되면 다중 탭이
        // 다시 로그아웃되고 원인이 이 한 줄이라는 것을 알 방법이 없다. 접근자가 아니라 파라미터를
        // 보는 이유는 compact constructor 안에서는 필드가 아직 대입 전이라 접근자가 null 을 주기
        // 때문이다 (SurveyProperties 가 같은 함정을 적어 두었다).
        if (refreshReuseGrace == null || refreshReuseGrace.isNegative() || refreshReuseGrace.isZero()) {
            throw new IllegalStateException(
                    "app.auth.refresh-reuse-grace 는 0보다 커야 합니다: " + refreshReuseGrace);
        }
        // 같은 이유로 0 을 거른다. 0 이면 갱신하는 순간 다른 쪽 토큰이 다시 즉사하는데, 설정에는
        // 값이 있어 보여 GitLab #216 이 조용히 되살아난다.
        if (sessionRotationGrace == null || sessionRotationGrace.isNegative() || sessionRotationGrace.isZero()) {
            throw new IllegalStateException(
                    "app.auth.session-rotation-grace 는 0보다 커야 합니다: " + sessionRotationGrace);
        }
        // 유예가 Access Token 수명보다 길면 옛 토큰은 유예로 사는 것이 아니라 자기 exp 로 죽는다 —
        // 그 상태의 큰 값은 실제 동작을 바꾸지 않으면서 "길게 열어 뒀다" 는 오해만 남긴다.
        if (accessTokenTtl != null && sessionRotationGrace.compareTo(accessTokenTtl) > 0) {
            throw new IllegalStateException("app.auth.session-rotation-grace 는 access-token-ttl 보다 "
                    + "길 수 없습니다: " + sessionRotationGrace + " > " + accessTokenTtl);
        }
    }
}
