package com.example.ssafesta.auth;

import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class OAuthCompletionController {
    static final String HANDOFF_COOKIE = "oauth_handoff";
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth 로그인 handoff cookie가 없습니다.");
        }
        if (handoffs.kind(handoff) == OAuthHandoffService.Kind.REGISTRATION
                && (request == null || request.nickname() == null || request.nickname().isBlank())) {
            return ResponseEntity.ok(OAuthCompletionResponse.nicknameRequired());
        }

        MemberSessionService.MemberSession session;
        if (handoffs.kind(handoff) == OAuthHandoffService.Kind.MEMBER) {
            session = handoffs.consumeMember(handoff);
        } else {
            OAuthHandoffService.PendingRegistration pending = handoffs.consumeRegistration(handoff);
            RegistrationService.RegistrationResult registered = registrations.complete(
                    pending.provider(), pending.providerSubject(), request.nickname());
            session = sessions.issue(registered.userId());
        }
        return ResponseEntity.ok().header("Set-Cookie", clearHandoffCookie().toString())
                .header("Set-Cookie", refreshCookie(session.refreshToken()).toString())
                .body(OAuthCompletionResponse.authenticated(session.accessToken(), session.expiresAt()));
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from("refresh_token", value).httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path(properties.refreshCookiePath()).maxAge(properties.refreshTokenTtl()).build();
    }

    private ResponseCookie clearHandoffCookie() {
        return ResponseCookie.from(HANDOFF_COOKIE, "").httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/api/v1/auth/oauth/complete").maxAge(0).build();
    }

    public record CompleteOAuthRequest(String nickname) { }
    public record OAuthCompletionResponse(String status, String accessToken, Instant expiresAt) {
        static OAuthCompletionResponse nicknameRequired() { return new OAuthCompletionResponse("NICKNAME_REQUIRED", null, null); }
        static OAuthCompletionResponse authenticated(String accessToken, Instant expiresAt) {
            return new OAuthCompletionResponse("AUTHENTICATED", accessToken, expiresAt);
        }
    }
}
