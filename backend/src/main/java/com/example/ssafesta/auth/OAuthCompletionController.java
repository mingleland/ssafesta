package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Parameter;
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

    public record CompleteOAuthRequest(String nickname) { }
    public record OAuthCompletionResponse(String status, String accessToken, Instant expiresAt) {
        static OAuthCompletionResponse nicknameRequired() { return new OAuthCompletionResponse("NICKNAME_REQUIRED", null, null); }
        static OAuthCompletionResponse authenticated(String accessToken, Instant expiresAt) {
            return new OAuthCompletionResponse("AUTHENTICATED", accessToken, expiresAt);
        }
    }
}
