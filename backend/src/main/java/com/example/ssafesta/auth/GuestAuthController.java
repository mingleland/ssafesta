package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Auth")
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

    @Operation(summary = "게스트 토큰 발급 — 로그인 없이 관람만 하는 토큰",
            description = """
                    로그인하지 않은 방문자가 월드를 둘러볼 때 쓰는 Access Token 을 발급한다. 요청 본문도 인증도 필요 없다.

                    **게스트로 할 수 있는 것과 없는 것**

                    - 할 수 있다: 슬롯 목록, 부스 정보, 공개된 배치·프로젝트·게임 조회
                    - 할 수 없다: 부스 임대, 지갑·코인, 내 정보, 게임 제작, AI 직원 설정 → `403 MEMBER_ONLY`

                    게스트에게는 지갑도 코인도 없고, 아바타 외형도 서버에 저장하지 않는다 (헌법 12조).

                    **재발급이 없다** — 이 endpoint 는 refresh 쿠키를 내려주지 않으므로 만료되면 다시 이 endpoint 를 호출한다.
                    호출할 때마다 새 토큰이 나오는 것이 정상이고, 멱등하지 않다.
                    """)
    @ApiResponse(responseCode = "200", description = "발급 성공. `accessToken` 을 이후 요청의 `Authorization: Bearer` 로 보낸다")
    @PostMapping("/guest")
    public ResponseEntity<GuestTokenResponse> guestLogin() {
        AccessTokenService.IssuedAccessToken issued = accessTokenService.issueGuestToken();
        return ResponseEntity.ok(new GuestTokenResponse(issued.token(), issued.expiresAt()));
    }

    // required=false 라야 쿠키 부재가 Spring 의 400 이 아니라 우리의 401 이 된다 — 세션이 없는 것은
    // 요청이 잘못된 것이 아니다. (#113 당시에는 500 이었고, 그건 ErrorResponse 매칭으로 따로 고쳤다.)
    @Operation(summary = "Access Token 재발급 — refresh 쿠키로 회원 세션 연장",
            description = """
                    HttpOnly `refresh_token` 쿠키를 근거로 새 Access Token 을 발급하고, 쿠키도 새 값으로 교체한다(rotation).
                    Access Token 을 요청에 넣지 않는다 — 만료된 토큰으로도 호출하는 endpoint 이기 때문이다.

                    **FE 는 페이지 로드마다 한 번 호출한다.** Refresh Token 은 HttpOnly 라 FE 가 존재 여부를 읽을 수 없고
                    그것이 설계 의도다(헌법 13조). 따라서 **비로그인·게스트 방문자는 매번 `401` 을 받으며 그것이 정상 상태다** —
                    서버 오류로 취급하면 안 된다.

                    쿠키가 **없는 경우·만료된 경우·이미 쓰인 경우**가 모두 같은 `401 INVALID_MEMBER_TOKEN` 이다.
                    사용자에게는 "로그인돼 있지 않다" 하나의 사건이라 이름을 나누지 않았다.

                    Bruno 로 시험할 때는 쿠키 jar 에 저장된 값이 자동 전송된다. Refresh Token 을 본문이나 환경변수에 넣지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "새 Access Token 발급. 응답의 `Set-Cookie` 로 refresh 쿠키가 교체된다"),
            @ApiResponse(responseCode = "401", description = "`INVALID_MEMBER_TOKEN` — 쿠키가 없거나 만료·이미 사용됐다. 비로그인 방문자의 정상 응답이다"),
            @ApiResponse(responseCode = "403", description = "`UNTRUSTED_ORIGIN` — `Origin` 이 허용된 프론트 주소가 아니다. 쿠키를 보기 전에 먼저 거절한다")})
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

    @Operation(summary = "로그아웃 — 서버 세션 폐기 + refresh 쿠키 삭제",
            description = """
                    회원 토큰이면 서버의 세션을 폐기하고, 어느 토큰이든 `refresh_token` 쿠키를 `Max-Age=0` 으로 지운다.

                    **폐기는 서버에서 일어난다.** 세션이 폐기되면 아직 만료 전인 Access Token 도 이후 요청에서 `401` 이 된다 —
                    쿠키만 지우는 로그아웃은 복사해 둔 토큰을 살려 두므로 그렇게 하지 않는다.

                    게스트 토큰으로 호출하면 폐기할 서버 세션이 없어 쿠키만 지우고 `204` 다. 여러 번 호출해도 결과가 같다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "폐기 완료. 본문이 없고 `Set-Cookie` 로 refresh 쿠키가 삭제된다"),
            @ApiResponse(responseCode = "401", description = "Access Token 이 없거나 유효하지 않다"),
            @ApiResponse(responseCode = "403", description = "`UNTRUSTED_ORIGIN` — `Origin` 이 허용된 프론트 주소가 아니다")})
    @SecurityRequirement(name = "bearerAuth")
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

    @Schema(description = "발급된 Access Token 과 만료 시각. 게스트 발급·재발급이 같은 모양을 쓴다")
    public record GuestTokenResponse(
            @Schema(description = "`Authorization: Bearer <이 값>` 으로 보낸다. 안에 `role`(GUEST·MEMBER)과 만료가 들어 있다",
                    example = "eyJhbGciOiJIUzUxMiJ9.eyJyb2xlIjoiR1VFU1QifQ.…")
            String accessToken,
            @Schema(description = "만료 시각(UTC). 이 시각이 지나면 회원은 `/auth/refresh`, 게스트는 `/auth/guest` 를 다시 호출한다",
                    example = "2026-09-02T06:00:00Z")
            Instant expiresAt) {
    }

    private void requireTrustedOrigin(String origin, AuthProperties properties) {
        if (!properties.frontendBaseUrl().equals(origin)) {
            throw new ApiException(ErrorCode.UNTRUSTED_ORIGIN);
        }
    }

}
