package com.example.ssafesta.game;

import com.example.ssafesta.common.MemberPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Game-flavoured wrapper over {@link MemberPrincipal} (헌법 12조).
 *
 * <p>Guests are refused for Authoring only. Playing a published game stays open to them (FR-023), so
 * the Runtime endpoint asks for {@link #optionalMemberId} and never this.
 */
final class GamePrincipal {

    private static final String MEMBER_ONLY = "회원 계정만 게임을 만들 수 있습니다.";

    private GamePrincipal() {
    }

    /** The member id, or {@code null} for an anonymous or guest viewer. */
    static Long optionalMemberId(Jwt jwt) {
        return MemberPrincipal.optionalMemberId(jwt);
    }

    static Long requireMemberId(Jwt jwt) {
        return MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
    }
}
