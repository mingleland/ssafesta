package com.example.ssafesta.wallet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Grants the daily coins on a member's first authenticated request of the KST day
 * (spec 003 FR-003).
 *
 * <p>Placed on every authenticated request rather than on login, because a page refresh reuses a
 * still-valid access token and never goes through login — checking only at session issue would
 * miss the case the spec calls out explicitly (research R-05).
 */
public class DailyCoinGrantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DailyCoinGrantInterceptor.class);
    private static final String MEMBER_ROLE = "MEMBER";

    private final DailyCoinGrantService dailyGrants;

    public DailyCoinGrantInterceptor(DailyCoinGrantService dailyGrants) {
        this.dailyGrants = dailyGrants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = authenticatedMemberId();
        if (userId == null) {
            return true;
        }
        try {
            dailyGrants.grantIfDue(userId);
        } catch (RuntimeException exception) {
            // The grant is a side benefit of being logged in; failing it must not take down an
            // unrelated API call (헌법 3조의 장애 격리). It is idempotent, so the next request
            // retries, and a persistent failure shows up here and in the reconciliation check.
            // What must never happen is failing quietly (T-24) — hence ERROR, not a swallow.
            log.error("일일 코인 지급 실패 — 요청은 계속 진행합니다. userId={}, uri={}",
                    userId, request.getRequestURI(), exception);
        }
        return true;
    }

    private Long authenticatedMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        if (!MEMBER_ROLE.equals(jwt.getClaimAsString("role"))) {
            return null; // Guests have no wallet at all (spec 003 FR-003b).
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            log.warn("MEMBER 토큰의 subject가 회원 식별자가 아닙니다 — subject={}", jwt.getSubject());
            return null;
        }
    }
}
