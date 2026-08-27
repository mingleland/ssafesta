package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssafesta.user.OAuthIdentity;
import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * The suspended-account branch of the OAuth success handler.
 *
 * <p>This runs inside the security filter chain, where {@code @RestControllerAdvice} never reaches.
 * It used to throw a plain {@code RuntimeException}, which nothing caught — the browser was left on
 * the backend's own callback URL with a raw error and never returned to the app (#113 sweep).
 */
class OAuthLoginSuccessHandlerTest {

    private static final String FRONTEND = "http://localhost:5173";

    private final OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
    private final MemberSessionService sessions = mock(MemberSessionService.class);
    private final OAuthHandoffService handoffs = mock(OAuthHandoffService.class);
    private final AuthProperties properties = new AuthProperties("secret", Duration.ofMinutes(30),
            Duration.ofDays(14), Duration.ofMinutes(5), "/api/v1/auth/refresh", FRONTEND, false);

    private final OAuthLoginSuccessHandler handler =
            new OAuthLoginSuccessHandler(identities, sessions, handoffs, properties);

    @Test
    void aSuspendedAccountIsSentBackToTheAppWithAReasonAndNoSession() throws Exception {
        MockHttpServletResponse response = refuse(suspended());

        assertEquals(FRONTEND + "/auth/callback?error=ACCOUNT_SUSPENDED", response.getRedirectedUrl(),
                "정지 계정은 앱으로 돌아가야 하고, 이유가 실려야 한다.");
        verify(sessions, never()).issue(anyLong());
        verify(handoffs, never()).createMember(any());
    }

    /**
     * The redirect alone is not enough.
     *
     * <p>A handoff from an earlier attempt lives up to five minutes, and the frontend calls
     * {@code complete} on every arrival at {@code /auth/callback}. Leaving a stale one in the browser
     * would let the refused visitor ride it into a session — the refusal has to delete it, with the
     * cookie's own attributes, or the browser keeps it.
     */
    @Test
    void aSuspendedAccountHasAnyLeftoverHandoffDeleted() throws Exception {
        String setCookie = refuse(suspended()).getHeader("Set-Cookie");

        assertNotNull(setCookie, "잔존 handoff 를 지우는 Set-Cookie 가 있어야 한다.");
        assertTrue(setCookie.startsWith("oauth_handoff="), "handoff 쿠키를 대상으로 해야 한다: " + setCookie);
        assertTrue(setCookie.contains("Max-Age=0"), "삭제 쿠키여야 한다: " + setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/auth/oauth/complete"),
                "Path 가 원본과 같아야 브라우저가 지운다: " + setCookie);
    }

    private MockHttpServletResponse refuse(User user) throws Exception {
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject"))
                .thenReturn(Optional.of(new OAuthIdentity(user, OAuthProvider.GOOGLE, "subject")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, googleToken());
        return response;
    }

    private User suspended() {
        User user = new User("정지된회원");
        user.suspend("ADMIN_SUSPEND");
        return user;
    }

    private OAuth2AuthenticationToken googleToken() {
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "subject"), "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }
}
