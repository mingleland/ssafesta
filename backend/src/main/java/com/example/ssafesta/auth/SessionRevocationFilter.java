package com.example.ssafesta.auth;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects revoked member access tokens before a protected endpoint can use them. */
public class SessionRevocationFilter extends OncePerRequestFilter {
    private final MemberSessionService sessions;

    public SessionRevocationFilter(MemberSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && "MEMBER".equals(jwt.getClaimAsString("role"))) {
            try {
                Long userId = Long.valueOf(jwt.getSubject());
                if (!sessions.isActive(userId, jwt.getClaimAsString("sid"))) {
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "로그인 세션이 종료되었습니다.");
                    return;
                }
            } catch (NumberFormatException ignored) {
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 회원 토큰입니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
