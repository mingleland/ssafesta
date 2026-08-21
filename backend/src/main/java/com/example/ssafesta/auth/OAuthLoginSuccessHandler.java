package com.example.ssafesta.auth;

import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.AccountStatus;
import java.io.IOException;
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
                if (identity.getUser().getStatus() != AccountStatus.ACTIVE) throw new AccountUnavailableException();
                MemberSessionService.MemberSession session = sessions.issue(identity.getUser().getId());
                redirectWithHandoff(response, handoffs.createMember(session));
            } catch (IOException exception) { throw new OAuthRedirectException(exception); }
        }, () -> {
            try {
                redirectWithHandoff(response, handoffs.createRegistration(provider, subject));
            } catch (IOException exception) { throw new OAuthRedirectException(exception); }
        });
    }

    private void redirectWithHandoff(HttpServletResponse response, String handoff) throws IOException {
        ResponseCookie cookie = ResponseCookie.from(OAuthCompletionController.HANDOFF_COOKIE, handoff)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax").path("/api/v1/auth/oauth/complete")
                .maxAge(properties.oauthStateTtl()).build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.sendRedirect(properties.frontendBaseUrl() + "/auth/callback");
    }

    private static class OAuthRedirectException extends RuntimeException { OAuthRedirectException(IOException cause) { super(cause); } }
}
