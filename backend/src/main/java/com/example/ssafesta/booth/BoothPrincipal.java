package com.example.ssafesta.booth;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the member behind a request.
 *
 * <p>Guests are refused explicitly rather than left to fail later for want of a wallet: 헌법 12조
 * forbids guests from leasing at all, and the code should say so (spec 004 FR-016, research R-09).
 */
final class BoothPrincipal {

    private static final String MEMBER_ROLE = "MEMBER";

    private BoothPrincipal() {
    }

    /** The member id, or {@code null} for an anonymous or guest viewer. */
    static Long optionalMemberId(Jwt jwt) {
        if (jwt == null || !MEMBER_ROLE.equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static Long requireMemberId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access Token이 필요합니다.");
        }
        if (!MEMBER_ROLE.equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회원 계정만 부스를 임대할 수 있습니다.");
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 회원 토큰입니다.");
        }
    }
}
