package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiErrorWriter;
import com.example.ssafesta.common.ErrorCode;
import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects revoked member access tokens before a protected endpoint can use them.
 *
 * <p>Both refusals are written through {@link ApiErrorWriter}, never {@code response.sendError}.
 * The latter hands the response to the container's error page, which in this application means a
 * 401 with an <b>empty body</b> — no {@code code}, no {@code requestId} — while every other refusal
 * in the filter chain carries the documented envelope (docs/08 §1.3). A client branching on
 * {@code code} saw nothing to branch on after logging out in another tab (S15P21A604-693, T-157).
 */
public class SessionRevocationFilter extends OncePerRequestFilter {

    /**
     * 이 거절은 <b>세션이 통째로 사라진 사건을 서버에서 알아차릴 수 있는 유일한 신호</b>다 —
     * 만료되지 않은 Access Token 을 들고 온 요청만 여기까지 오기 때문이다. 조용히 401 만 내보내면
     * 저장소가 비워진 것과 한 사람이 다른 곳에서 새로 로그인한 것이 로그에서 같아 보인다
     * (GitLab #211).
     */
    private static final Logger log = LoggerFactory.getLogger(SessionRevocationFilter.class);

    private final MemberSessionService sessions;
    private final ApiErrorWriter errors;

    public SessionRevocationFilter(MemberSessionService sessions, ApiErrorWriter errors) {
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && "MEMBER".equals(jwt.getClaimAsString("role"))) {
            Long userId;
            try {
                userId = Long.valueOf(jwt.getSubject());
            } catch (NumberFormatException malformed) {
                // A MEMBER token that cannot name a member — the same code the refresh path uses
                // for a token that identifies no session.
                SecurityContextHolder.clearContext();
                log.warn("세션 거절 — MEMBER 토큰의 subject 가 회원을 가리키지 않는다");
                errors.write(response, ErrorCode.INVALID_MEMBER_TOKEN, null);
                return;
            }
            MemberSessionService.SessionCheck check = sessions.check(userId, jwt.getClaimAsString("sid"));
            if (check != MemberSessionService.SessionCheck.ACTIVE) {
                SecurityContextHolder.clearContext();
                log.warn("세션 거절 — userId={} 사유={} 경로={}", userId, check, request.getRequestURI());
                errors.write(response, ErrorCode.UNAUTHORIZED, "로그인 세션이 종료되었습니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
