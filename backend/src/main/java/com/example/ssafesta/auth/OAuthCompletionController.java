package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "Auth")
public class OAuthCompletionController {
    static final String HANDOFF_COOKIE = "oauth_handoff";
    /** Scoping the cookie to the one endpoint that consumes it — it must not ride on any other call. */
    static final String HANDOFF_COOKIE_PATH = "/api/v1/auth/oauth/complete";
    private final OAuthHandoffService handoffs;
    private final RegistrationService registrations;
    private final MemberSessionService sessions;
    private final AuthProperties properties;

    public OAuthCompletionController(OAuthHandoffService handoffs, RegistrationService registrations,
                                     MemberSessionService sessions, AuthProperties properties) {
        this.handoffs = handoffs;
        this.registrations = registrations;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Operation(summary = "소셜 로그인 완료 — 기존 회원 로그인 또는 신규 가입",
            description = """
                    동의 화면을 통과한 뒤 프론트 callback 화면이 호출하는 endpoint 다. Access Token 이 아니라
                    **1회용 `oauth_handoff` 쿠키**(HttpOnly, 5분)로 인증한다. 그래서 자물쇠가 없다.

                    브라우저에서는 `credentials: 'include'` 로 호출하면 쿠키가 자동 전송된다.

                    **한 번에 끝나거나, 두 번에 끝난다**

                    | 호출 | 응답 `status` | 다음 |
                    |---|---|---|
                    | 본문 없이(또는 `{}`) | `AUTHENTICATED` | 기존 회원이다. `accessToken` 을 받았으니 끝 |
                    | 본문 없이(또는 `{}`) | `NICKNAME_REQUIRED` | 신규 회원이다. 닉네임을 받아 같은 endpoint 를 `{"nickname": "…"}` 로 다시 호출한다 |

                    **닉네임이 거부되면 handoff 는 살아 있다.** 중복(`409`)이나 형식 위반(`400`)을 받은 사람이
                    다른 닉네임으로 다시 시도할 수 있다 — 먼저 소비해 버리면 모든 거부가 막다른 길이 된다 (FR-021c).
                    반대로 세션 발급까지 성공하면 handoff 는 즉시 폐기되고 재사용은 `410` 이다.

                    성공 응답은 `refresh_token` 쿠키(HttpOnly)도 심는다. 이후 Access Token 연장은
                    `POST /api/v1/auth/refresh` 가 담당한다.

                    Bruno 로 시험할 때만 개발자 도구에서 `oauth_handoff` 값을 복사해 환경변수에 넣는다.
                    실제 프론트는 그 값을 읽지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`AUTHENTICATED`(로그인 완료) 또는 `NICKNAME_REQUIRED`(닉네임이 더 필요하다). 둘 다 200 이며 `status` 로 가른다"),
            @ApiResponse(responseCode = "400", description = "`OAUTH_HANDOFF_MISSING`(쿠키가 없다) 또는 `VALIDATION_FAILED`(닉네임 형식 위반 — handoff 는 유지된다)"),
            @ApiResponse(responseCode = "409", description = "`NICKNAME_DUPLICATED` — 이미 쓰는 닉네임이다. handoff 가 유지되므로 다른 값으로 다시 호출한다"),
            @ApiResponse(responseCode = "410", description = "`OAUTH_HANDOFF_EXPIRED` — 5분이 지났거나 이미 소비된 handoff 다. 소셜 로그인을 처음부터 다시 한다")})
    @PostMapping("/complete")
    public ResponseEntity<OAuthCompletionResponse> complete(
            @Parameter(hidden = true) @CookieValue(name = HANDOFF_COOKIE, required = false) String handoff,
            @RequestBody(required = false) CompleteOAuthRequest request) {
        if (handoff == null || handoff.isBlank()) {
            throw new ApiException(ErrorCode.OAUTH_HANDOFF_MISSING);
        }
        if (handoffs.kind(handoff) == OAuthHandoffService.Kind.REGISTRATION
                && (request == null || request.nickname() == null || request.nickname().isBlank())) {
            return ResponseEntity.ok(OAuthCompletionResponse.nicknameRequired());
        }

        MemberSessionService.MemberSession session;
        if (handoffs.kind(handoff) == OAuthHandoffService.Kind.MEMBER) {
            session = handoffs.consumeMember(handoff);
        } else {
            // Read, then register, then spend. A refused nickname leaves the handoff alive so the
            // person can submit another one — spending it first turned every 409 into a dead end
            // (FR-021c, review of !56).
            OAuthHandoffService.PendingRegistration pending = handoffs.peekRegistration(handoff);
            RegistrationService.RegistrationResult registered = registrations.complete(
                    pending.provider(), pending.providerSubject(), request.nickname());
            // The handoff is still worth exactly one session. Two callers holding the same one can
            // both get this far — registration is idempotent, so both succeed — but only the one
            // whose DELETE actually removed the key may issue, because issuing revokes whatever
            // session the account already had.
            if (!handoffs.discard(handoff)) {
                throw new InvalidOAuthHandoffException();
            }
            session = sessions.issue(registered.userId());
        }
        return ResponseEntity.ok().header("Set-Cookie", clearHandoffCookie(properties).toString())
                .header("Set-Cookie", refreshCookie(session.refreshToken()).toString())
                .body(OAuthCompletionResponse.authenticated(session.accessToken(), session.expiresAt()));
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from("refresh_token", value).httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path(properties.refreshCookiePath()).maxAge(properties.refreshTokenTtl()).build();
    }

    /** Deletion must match the cookie's own attributes; the suspended-account path clears it too. */
    static ResponseCookie clearHandoffCookie(AuthProperties properties) {
        return ResponseCookie.from(HANDOFF_COOKIE, "").httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path(HANDOFF_COOKIE_PATH).maxAge(0).build();
    }

    @Schema(description = "신규 가입일 때만 필요하다. 첫 호출은 본문을 비우거나 `{}` 로 보낸다")
    public record CompleteOAuthRequest(
            @Schema(description = "가입할 닉네임. 2~30자이며 이미 쓰는 값이면 409 다", example = "FESTA_USER")
            String nickname) { }

    @Schema(description = "`status` 로 두 결과를 가른다. `NICKNAME_REQUIRED` 면 토큰 필드가 비어 있다")
    public record OAuthCompletionResponse(
            @Schema(description = "`AUTHENTICATED`(로그인 완료) 또는 `NICKNAME_REQUIRED`(닉네임을 받아 재호출)",
                    allowableValues = {"AUTHENTICATED", "NICKNAME_REQUIRED"}, example = "AUTHENTICATED")
            String status,
            @Schema(description = "회원 Access Token. `NICKNAME_REQUIRED` 응답에서는 `null` 이다")
            String accessToken,
            @Schema(description = "Access Token 만료 시각(UTC). `NICKNAME_REQUIRED` 응답에서는 `null` 이다",
                    example = "2026-09-02T06:00:00Z")
            Instant expiresAt) {
        static OAuthCompletionResponse nicknameRequired() { return new OAuthCompletionResponse("NICKNAME_REQUIRED", null, null); }
        static OAuthCompletionResponse authenticated(String accessToken, Instant expiresAt) {
            return new OAuthCompletionResponse("AUTHENTICATED", accessToken, expiresAt);
        }
    }
}
