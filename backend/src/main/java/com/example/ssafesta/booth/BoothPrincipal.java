package com.example.ssafesta.booth;

import com.example.ssafesta.common.MemberPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Booth-flavoured wrapper over {@link MemberPrincipal} (spec 004 FR-016, 헌법 12조).
 *
 * <p>Only the guest-refusal wording differs — "부스를 임대할 수 없다" tells the user which action was
 * blocked, which a generic refusal does not.
 */
final class BoothPrincipal {

    private static final String MEMBER_ONLY = "회원 계정만 부스를 임대할 수 있습니다.";

    private BoothPrincipal() {
    }

    /** The member id, or {@code null} for an anonymous or guest viewer. */
    static Long optionalMemberId(Jwt jwt) {
        return MemberPrincipal.optionalMemberId(jwt);
    }

    static Long requireMemberId(Jwt jwt) {
        return MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
    }
}
