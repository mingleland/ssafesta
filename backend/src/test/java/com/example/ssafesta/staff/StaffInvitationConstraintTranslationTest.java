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
 * 초대 생성의 INSERT 가 깨뜨린 제약이 <b>무엇인지</b> 보고 답을 고르는지 (S15P21A604-693).
 *
 * <p>{@code DataIntegrityViolationException} 을 무조건 409 로 번역하면 외래키가 깨진 경우까지
 * "대기 중 초대가 있습니다" 가 된다. 컨테이너 없이 도는 단위 테스트다 —
 * {@code ProjectConstraintTranslationTest} 와 같은 모양.
 *
 * <p><b>수락 경로는 여기에 없다.</b> 그쪽의 쓰기는 {@code seatIfAbsent} 라 위반이 아니라 행 수 0
 * 으로 거절을 알린다 — 번역할 예외가 없다 (GitLab #190,
 * {@code BoothStaffRepository#seatIfAbsent}). 그 거절은
 * {@code StaffInvitationServiceTranslationTest} 와
 * {@code StaffInvitationConcurrencyIntegrationTest} 가 본다.
 */
class StaffInvitationConstraintTranslationTest {

    @Test
    void thePendingUniqueIndexMeansAnInvitationIsAlreadyWaiting() {
        RuntimeException translated =
                StaffInvitationService.translateInvite(violationOf("ux_staff_invitations_pending"));

        assertEquals(ErrorCode.STAFF_INVITATION_PENDING, assertInstanceOf(ApiException.class, translated).errorCode());
    }

    /** 대소문자는 드라이버·DB 마다 다르게 온다. 판정이 그것에 걸리면 안 된다. */
    @Test
    void constraintNamesAreMatchedCaseInsensitively() {
        assertInstanceOf(ApiException.class,
                StaffInvitationService.translateInvite(violationOf("UX_STAFF_INVITATIONS_PENDING")));
    }

    /** 외래키 위반은 중복이 아니다 — 삼키지 않고 원본을 그대로 올린다 (T-24). */
    @Test
    void aForeignKeyViolationIsNotTranslated() {
        DataIntegrityViolationException invite = violationOf("staff_invitations_booth_id_fkey");

        assertSame(invite, StaffInvitationService.translateInvite(invite));
    }

    /** 드라이버가 제약 이름을 안 주면 추측하지 않는다. */
    @Test
    void anUnnamedViolationIsNotTranslated() {
        DataIntegrityViolationException unnamed = new DataIntegrityViolationException("이름 없는 위반");

        assertSame(unnamed, StaffInvitationService.translateInvite(unnamed));
    }

    /** 남의 제약은 번역하지 않는다 — 자리 PK 를 초대 경로가 집으면 틀린 409 가 된다. */
    @Test
    void theSeatConstraintIsNotTranslatedByTheInvitePath() {
        DataIntegrityViolationException seat = violationOf("booth_staffs_pkey");

        assertSame(seat, StaffInvitationService.translateInvite(seat));
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("위반",
                new ConstraintViolationException("위반", new SQLException("23505"), constraint));
    }
}
