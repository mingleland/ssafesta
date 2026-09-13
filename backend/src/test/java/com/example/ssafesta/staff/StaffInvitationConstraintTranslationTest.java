package com.example.ssafesta.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 초대 수락·생성의 INSERT 가 깨뜨린 제약이 <b>무엇인지</b> 보고 답을 고르는지 (S15P21A604-693).
 *
 * <p>{@code DataIntegrityViolationException} 을 무조건 409 로 번역하면 외래키가 깨진 경우까지
 * "이미 구성원입니다" 가 된다. 컨테이너 없이 도는 단위 테스트다 —
 * {@code ProjectConstraintTranslationTest} 와 같은 모양.
 */
class StaffInvitationConstraintTranslationTest {

    @Test
    void theStaffPrimaryKeyMeansTheMemberAlreadyHoldsASeat() {
        RuntimeException translated = StaffInvitationService.translateAccept(violationOf("booth_staffs_pkey"));

        assertEquals(ErrorCode.STAFF_ALREADY_MEMBER, assertInstanceOf(ApiException.class, translated).errorCode());
    }

    @Test
    void thePendingUniqueIndexMeansAnInvitationIsAlreadyWaiting() {
        RuntimeException translated =
                StaffInvitationService.translateInvite(violationOf("ux_staff_invitations_pending"));

        assertEquals(ErrorCode.STAFF_INVITATION_PENDING, assertInstanceOf(ApiException.class, translated).errorCode());
    }

    /** 대소문자는 드라이버·DB 마다 다르게 온다. 판정이 그것에 걸리면 안 된다. */
    @Test
    void constraintNamesAreMatchedCaseInsensitively() {
        assertInstanceOf(ApiException.class, StaffInvitationService.translateAccept(violationOf("BOOTH_STAFFS_PKEY")));
        assertInstanceOf(ApiException.class,
                StaffInvitationService.translateInvite(violationOf("UX_STAFF_INVITATIONS_PENDING")));
    }

    /** 외래키 위반은 중복이 아니다 — 삼키지 않고 원본을 그대로 올린다 (T-24). */
    @Test
    void aForeignKeyViolationIsNotTranslated() {
        DataIntegrityViolationException accept = violationOf("booth_staffs_user_id_fkey");
        DataIntegrityViolationException invite = violationOf("staff_invitations_booth_id_fkey");

        assertSame(accept, StaffInvitationService.translateAccept(accept));
        assertSame(invite, StaffInvitationService.translateInvite(invite));
    }

    /** 드라이버가 제약 이름을 안 주면 추측하지 않는다. */
    @Test
    void anUnnamedViolationIsNotTranslated() {
        DataIntegrityViolationException unnamed = new DataIntegrityViolationException("이름 없는 위반");

        assertSame(unnamed, StaffInvitationService.translateAccept(unnamed));
        assertSame(unnamed, StaffInvitationService.translateInvite(unnamed));
    }

    /** 수락의 표와 초대의 표는 섞이지 않는다 — 서로의 제약을 번역하면 틀린 409 가 된다. */
    @Test
    void eachPathTranslatesOnlyItsOwnConstraint() {
        DataIntegrityViolationException pending = violationOf("ux_staff_invitations_pending");
        DataIntegrityViolationException seat = violationOf("booth_staffs_pkey");

        assertSame(pending, StaffInvitationService.translateAccept(pending));
        assertSame(seat, StaffInvitationService.translateInvite(seat));
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("위반",
                new ConstraintViolationException("위반", new SQLException("23505"), constraint));
    }
}
