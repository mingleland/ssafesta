package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class GuestAuthController {

    private final AccessTokenService accessTokenService;
    private final MemberSessionService memberSessionService;
    private final AuthProperties properties;

    public GuestAuthController(AccessTokenService accessTokenService, MemberSessionService memberSessionService,
                               AuthProperties properties) {
        this.accessTokenService = accessTokenService;
        this.memberSessionService = memberSessionService;
        this.properties = properties;
    }

    @PostMapping("/guest")
    public ResponseEntity<GuestTokenResponse> guestLogin() {
        AccessTokenService.IssuedAccessToken issued = accessTokenService.issueGuestToken();
        return ResponseEntity.ok(new GuestTokenResponse(issued.token(), issued.expiresAt()));
    }

    // required=false 라야 쿠키 부재가 Spring 의 400 이 아니라 우리의 401 이 된다 — 세션이 없는 것은
    // 요청이 잘못된 것이 아니다. (#113 당시에는 500 이었고, 그건 ErrorResponse 매칭으로 따로 고쳤다.)
    @PostMapping("/refresh")
    public ResponseEntity<GuestTokenResponse> refresh(@Parameter(hidden = true) @CookieValue(name = "refresh_token", required = false) String refreshToken,
                                                        @Parameter(hidden = true) @RequestHeader(name = "Origin", required = false) String origin) {
        requireTrustedOrigin(origin, properties);
        if (refreshToken == null) {
            throw new InvalidRefreshTokenException();
        }
        MemberSessionService.MemberSession session = memberSessionService.refresh(refreshToken);
        ResponseCookie cookie = ResponseCookie.from("refresh_token", session.refreshToken())
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax").path(properties.refreshCookiePath())
                .maxAge(properties.refreshTokenTtl()).build();
        return ResponseEntity.ok().header("Set-Cookie", cookie.toString())
                .body(new GuestTokenResponse(session.accessToken(), session.expiresAt()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,
                                       @Parameter(hidden = true) @RequestHeader(name = "Origin", required = false) String origin) {
        requireTrustedOrigin(origin, properties);
        if (jwt != null && "MEMBER".equals(jwt.getClaimAsString("role"))) {
            try {
                memberSessionService.revoke(Long.valueOf(jwt.getSubject()));
            } catch (NumberFormatException ignored) {
                // A malformed member subject cannot identify a server-side session to revoke.
            }
        }
        ResponseCookie cleared = ResponseCookie.from("refresh_token", "").httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Lax").path(properties.refreshCookiePath()).maxAge(0).build();
        return ResponseEntity.noContent().header("Set-Cookie", cleared.toString()).build();
    }

    public record GuestTokenResponse(String accessToken, Instant expiresAt) {
    }

    private void requireTrustedOrigin(String origin, AuthProperties properties) {
        if (!properties.frontendBaseUrl().equals(origin)) {
            throw new ApiException(ErrorCode.UNTRUSTED_ORIGIN);
        }
    }

}
