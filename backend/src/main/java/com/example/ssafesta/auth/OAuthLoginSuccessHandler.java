package com.example.ssafesta.auth;

import com.example.ssafesta.user.OAuthIdentityRepository;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.AccountStatus;
import java.io.IOException;
import java.util.Locale;
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
import jakarta.servlet.http.HttpSession;

@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginSuccessHandler.class);

    /**
     * Carried on the redirect, not in an {@code ErrorCode} — this channel is a browser navigation,
     * not the JSON envelope, so the vocabulary belongs to the OAuth contract document.
     */
    static final String SUSPENDED_ERROR = "ACCOUNT_SUSPENDED";

    /**
     * OAuth 시작 요청이 검증을 통과시킨 복귀 origin 이 담기는 자리 (GitLab #177).
     *
     * <p>쓰는 쪽은 {@link OAuthAuthorizationController}, 읽는 쪽은 여기다 — handoff 쿠키 상수가
     * 읽는 쪽인 {@code OAuthCompletionController} 에 사는 것과 같은 이유로 읽는 쪽에 둔다.
     */
    static final String RETURN_ORIGIN_SESSION_ATTRIBUTE = "ssafesta.oauth.returnOrigin";

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
        // 한 번만 꺼내고 바로 지운다 — 아래 두 갈래(정상·정지)가 같은 값을 써야 하고, 남겨 두면
        // 다음 로그인이 지난 흐름의 포트로 돌아간다.
        String returnOrigin = consumeReturnOrigin(request);
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuthProvider provider = OAuthProvider.valueOf(
                oauth.getAuthorizedClientRegistrationId().toUpperCase(Locale.ROOT));
        OAuth2User principal = oauth.getPrincipal();
        // The subject attribute differs per provider — "sub" on Google, "id" on Kakao, "userId" on
        // SSAFY — and each registration already names it as its user-name-attribute, which is what
        // getName() reads. Branching here instead would silently hand back null for whichever
        // provider was added last, and null subjects register a new account on every login.
        String subject = principal.getName();
        identities.findByProviderAndProviderSubject(provider, subject).ifPresentOrElse(identity -> {
            try {
                if (identity.getUser().getStatus() != AccountStatus.ACTIVE) {
                    refuseSuspended(response, returnOrigin, identity.getUser().getId());
                    return;
                }
                MemberSessionService.MemberSession session = sessions.issue(identity.getUser().getId());
                redirectWithHandoff(response, returnOrigin, handoffs.createMember(session));
            } catch (IOException exception) { throw new OAuthRedirectException(exception); }
        }, () -> {
            try {
                redirectWithHandoff(response, returnOrigin, handoffs.createRegistration(provider, subject));
            } catch (IOException exception) { throw new OAuthRedirectException(exception); }
        });
    }

    /**
     * Refuse without a session (FR-021): redirect with the reason and clear any handoff left from
     * an earlier attempt — it lives five minutes and the frontend calls complete on arrival.
     * Throwing here reached nobody; the filter chain never passes {@code @RestControllerAdvice}.
     */
    private void refuseSuspended(HttpServletResponse response, String returnOrigin, Long userId) throws IOException {
        log.warn("정지된 계정의 소셜 로그인을 거부했습니다 — userId={}", userId);
        response.addHeader("Set-Cookie", OAuthCompletionController.clearHandoffCookie(properties).toString());
        response.sendRedirect(returnOrigin + "/auth/callback?error=" + SUSPENDED_ERROR);
    }

    /**
     * 시작 단계에서 검증을 통과한 복귀 origin 을 1회용으로 소비한다. 없으면 {@code frontendBaseUrl} —
     * 오늘의 동작이다.
     *
     * <p><b>콜백 요청의 ownOrigin 과 다시 비교하지 않는다.</b> provider 콜백은 등록된 redirect URI
     * 때문에 {@code localhost:8080/login/oauth2/code/*} 로 들어오므로, 여기서 재검증하면 저장값(5175)과
     * 콜백 Host(8080)가 영원히 어긋나 항상 fallback 이 된다. 신뢰 판단은 시작 요청에서 이미 끝났다.
     *
     * <p>세션은 Spring 이 authorization request 를 담으려고 어차피 만드는 {@code HttpSession} 이다 —
     * 새 저장소도 새 쿠키도 늘리지 않는다. 인증 성공 시 session fixation 보호가 세션 ID 를 바꾸지만
     * 기본 전략({@code changeSessionId})은 세션 객체를 그대로 두므로 속성이 살아남는다.
     */
    private String consumeReturnOrigin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return properties.frontendBaseUrl();
        }
        Object stored = session.getAttribute(RETURN_ORIGIN_SESSION_ATTRIBUTE);
        session.removeAttribute(RETURN_ORIGIN_SESSION_ATTRIBUTE);
        return stored instanceof String origin ? origin : properties.frontendBaseUrl();
    }

    private void redirectWithHandoff(HttpServletResponse response, String returnOrigin, String handoff) throws IOException {
        ResponseCookie cookie = ResponseCookie.from(OAuthCompletionController.HANDOFF_COOKIE, handoff)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax").path(OAuthCompletionController.HANDOFF_COOKIE_PATH)
                .maxAge(properties.oauthStateTtl()).build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.sendRedirect(returnOrigin + "/auth/callback");
    }

    private static class OAuthRedirectException extends RuntimeException { OAuthRedirectException(IOException cause) { super(cause); } }
}
