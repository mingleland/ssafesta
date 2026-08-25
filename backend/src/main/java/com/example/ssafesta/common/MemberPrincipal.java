package com.example.ssafesta.common;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the member behind a request.
 *
 * <p>The same three checks — token present, role is {@code MEMBER}, subject is a number — were
 * written out separately in the booth, wallet and account controllers. Booth Studio would have made
 * a fourth copy, which is where duplication stops being harmless: one copy eventually forgets the
 * role check and a guest slips through.
 *
 * <p>Guests are refused explicitly rather than left to fail later for want of a wallet or a booth:
 * 헌법 12조 forbids guest ownership outright, and the code should say so at the door.
 */
public final class MemberPrincipal {

    private static final String MEMBER_ROLE = "MEMBER";

    private MemberPrincipal() {
    }

    /** The member id, or {@code null} for an anonymous or guest viewer. */
    public static Long optionalMemberId(Jwt jwt) {
        if (jwt == null || !MEMBER_ROLE.equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static Long requireMemberId(Jwt jwt) {
        return requireMemberId(jwt, ErrorCode.MEMBER_ONLY.defaultMessage());
    }

    /**
     * @param memberOnlyMessage what to tell a guest — worth tailoring per feature ("회원 계정만 부스를
     *                          임대할 수 있습니다."), since a generic refusal leaves the user guessing
     *                          which action was blocked
     */
    public static Long requireMemberId(Jwt jwt, String memberOnlyMessage) {
        if (jwt == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        if (!MEMBER_ROLE.equals(jwt.getClaimAsString("role"))) {
            throw new ApiException(ErrorCode.MEMBER_ONLY, memberOnlyMessage);
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.INVALID_MEMBER_TOKEN);
        }
    }
}
