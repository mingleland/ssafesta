package com.example.ssafesta.auth;

import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.AccountStatus;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginSuccessHandler.class);

    /**
     * Carried on the redirect, not in an {@code ErrorCode} — this channel is a browser navigation,
     * not the JSON envelope, so the vocabulary belongs to the OAuth contract document.
     */
    static final String SUSPENDED_ERROR = "ACCOUNT_SUSPENDED";

    private final OAuthIdentityRepository identities;
    private final MemberSessionService sessions;
    private final OAuthHandoffService handoffs;
    private final AuthProperties properties;

    public OAuthLoginSuccessHandler(OAuthIdentityRepository identities, MemberSessionService sessions,
                                    OAuthHandoffService handoffs, AuthProperties properties) {
        this.identities = identities;
        this.sessions = sessions;
        this.handoffs = handoffs;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuthProvider provider = OAuthProvider.valueOf(oauth.getAuthorizedClientRegistrationId().toUpperCase());
        OAuth2User principal = oauth.getPrincipal();
        Object subjectValue = principal.getAttribute(provider == OAuthProvider.GOOGLE ? "sub" : "id");
        String subject = subjectValue != null ? subjectValue.toString() : null;
        identities.findByProviderAndProviderSubject(provider, subject).ifPresentOrElse(identity -> {
            try {
                if (identity.getUser().getStatus() != AccountStatus.ACTIVE) {
                    refuseSuspended(response, identity.getUser().getId());
                    return;
                }
                MemberSessionService.MemberSession session = sessions.issue(identity.getUser().getId());
                redirectWithHandoff(response, handoffs.createMember(session));
            } catch (IOException exception) { throw new OAuthRedirectException(exception); }
        }, () -> {
            try {
                redirectWithHandoff(response, handoffs.createRegistration(provider, subject));
            } catch (IOException exception) { throw new OAuthRedirectException(exception); }
        });
    }

    /**
     * Refuse without a session (FR-021): redirect with the reason and clear any handoff left from
     * an earlier attempt — it lives five minutes and the frontend calls complete on arrival.
     * Throwing here reached nobody; the filter chain never passes {@code @RestControllerAdvice}.
     */
    private void refuseSuspended(HttpServletResponse response, Long userId) throws IOException {
        log.warn("정지된 계정의 소셜 로그인을 거부했습니다 — userId={}", userId);
        response.addHeader("Set-Cookie", OAuthCompletionController.clearHandoffCookie(properties).toString());
        response.sendRedirect(properties.frontendBaseUrl() + "/auth/callback?error=" + SUSPENDED_ERROR);
    }

    private void redirectWithHandoff(HttpServletResponse response, String handoff) throws IOException {
        ResponseCookie cookie = ResponseCookie.from(OAuthCompletionController.HANDOFF_COOKIE, handoff)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax").path(OAuthCompletionController.HANDOFF_COOKIE_PATH)
                .maxAge(properties.oauthStateTtl()).build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.sendRedirect(properties.frontendBaseUrl() + "/auth/callback");
    }

    private static class OAuthRedirectException extends RuntimeException { OAuthRedirectException(IOException cause) { super(cause); } }
}
