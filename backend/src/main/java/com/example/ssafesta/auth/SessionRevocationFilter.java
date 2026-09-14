package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiErrorWriter;
import com.example.ssafesta.common.ErrorCode;
import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
                errors.write(response, ErrorCode.INVALID_MEMBER_TOKEN, null);
                return;
            }
            if (!sessions.isActive(userId, jwt.getClaimAsString("sid"))) {
                SecurityContextHolder.clearContext();
                errors.write(response, ErrorCode.UNAUTHORIZED, "로그인 세션이 종료되었습니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
