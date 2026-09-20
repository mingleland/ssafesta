package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.Instant;
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
            Duration.ofDays(14), Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(30),
            Duration.ofSeconds(30), "/api/v1/auth/refresh", FRONTEND, false);

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

    /**
     * 콜백 provenance (GitLab #177).
     *
     * <p>provider 콜백은 등록된 redirect URI 때문에 <b>8080 으로</b> 들어온다. 그래서 여기서 요청의
     * origin 을 다시 보면 항상 8080 이고, 시작 단계에서 확인해 둔 5175 를 잃는다. 이 테스트가
     * {@code FRONTEND} 로 떨어지면 로컬 로그인이 엉뚱한 포트로 돌아간다는 뜻이다.
     */
    @Test
    void theCallbackReturnsToTheOriginProvenAtTheStartNotToItsOwnHost() throws Exception {
        User member = new User("복귀회원");
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject"))
                .thenReturn(Optional.of(new OAuthIdentity(member, OAuthProvider.GOOGLE, "subject")));
        when(sessions.issue(any())).thenReturn(
                new MemberSessionService.MemberSession("access", Instant.EPOCH, "refresh"));
        MockHttpServletRequest callback = callbackCarrying("http://localhost:5175");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(callback, response, googleToken());

        assertEquals("http://localhost:5175/auth/callback", response.getRedirectedUrl(),
                "시작 때 증명된 origin 으로 돌아가야 한다.");
        assertNull(callback.getSession(false).getAttribute(OAuthLoginSuccessHandler.RETURN_ORIGIN_SESSION_ATTRIBUTE),
                "1회용이다 — 남으면 다음 로그인이 지난 흐름의 포트로 간다.");
    }

    /** 저장된 값이 없으면 오늘과 같다. 8080 직행·Swagger 절차가 이 갈래를 탄다. */
    @Test
    void withoutAProvenOriginTheCallbackFallsBackToTheConfiguredFrontend() throws Exception {
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject"))
                .thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, googleToken());

        assertEquals(FRONTEND + "/auth/callback", response.getRedirectedUrl());
    }

    /** 거부도 같은 규칙을 탄다 — 정지 계정만 5173 으로 튕기면 로컬에서 이유를 볼 수 없다. */
    @Test
    void aSuspendedAccountIsRefusedOnTheSameOriginTheLoginStartedFrom() throws Exception {
        MockHttpServletResponse response = refuse(suspended(), callbackCarrying("http://localhost:5175"));

        assertEquals("http://localhost:5175/auth/callback?error=ACCOUNT_SUSPENDED", response.getRedirectedUrl());
    }

    private MockHttpServletRequest callbackCarrying(String provenOrigin) {
        // provider 콜백이 도착하는 곳 — FE 포트가 아니라 백엔드 포트다.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.getSession(true).setAttribute(
                OAuthLoginSuccessHandler.RETURN_ORIGIN_SESSION_ATTRIBUTE, provenOrigin);
        return request;
    }

    private MockHttpServletResponse refuse(User user) throws Exception {
        return refuse(user, new MockHttpServletRequest());
    }

    private MockHttpServletResponse refuse(User user, MockHttpServletRequest request) throws Exception {
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "subject"))
                .thenReturn(Optional.of(new OAuthIdentity(user, OAuthProvider.GOOGLE, "subject")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, googleToken());
        return response;
    }

    /**
     * The subject attribute is named per provider — "sub" on Google, "id" on Kakao, "userId" on
     * SSAFY — and the handler used to pick between them with {@code provider == GOOGLE ? "sub" : "id"}.
     *
     * <p>SSAFY has no "id" attribute, so under that branch the subject came back {@code null},
     * {@code findByProviderAndProviderSubject} missed, and every login registered a new account.
     * Nothing threw. These two tests pin the value that reaches the repository and the handoff, so
     * the ternary cannot come back for whichever provider is added next.
     */
    @Test
    void aFirstSsafyLoginCarriesTheUserIdAttributeIntoRegistration() throws Exception {
        when(identities.findByProviderAndProviderSubject(OAuthProvider.SSAFY, "1234567"))
                .thenReturn(Optional.empty());

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(), ssafyToken());

        verify(handoffs).createRegistration(OAuthProvider.SSAFY, "1234567");
        verify(sessions, never()).issue(anyLong());
    }

    @Test
    void aReturningSsafyAccountIsFoundByItsUserIdAndGetsASession() throws Exception {
        User member = new User("싸피회원");
        when(identities.findByProviderAndProviderSubject(OAuthProvider.SSAFY, "1234567"))
                .thenReturn(Optional.of(new OAuthIdentity(member, OAuthProvider.SSAFY, "1234567")));
        when(sessions.issue(any())).thenReturn(
                new MemberSessionService.MemberSession("access", Instant.EPOCH, "refresh"));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(), ssafyToken());

        verify(sessions).issue(member.getId());
        verify(handoffs, never()).createRegistration(any(), any());
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

    /**
     * Shaped like the real SSAFY userInfo response — {@code userId}, {@code email}, {@code name} at
     * the top level, and deliberately no {@code sub} or {@code id} to fall back on.
     */
    private OAuth2AuthenticationToken ssafyToken() {
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("userId", "1234567", "email", "member@ssafy.com", "name", "황덕"), "userId");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "ssafy");
    }
}
