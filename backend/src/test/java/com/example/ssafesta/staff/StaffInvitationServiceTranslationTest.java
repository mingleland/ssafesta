package com.example.ssafesta.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothStaff;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.booth.StaffRole;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 공개 {@code accept}·{@code invite} 가 저장 시점의 제약 위반을 <b>실제로 잡아 번역하는지</b>
 * (S15P21A604-693).
 *
 * <p>{@code StaffInvitationConstraintTranslationTest} 는 번역표만 증명한다. 동시성 통합 테스트는
 * 사전 조회가 직렬화되면 catch 를 밟지 않고도 409 가 나와 옛 코드로도 초록일 수 있다. 그래서
 * 저장소가 이름 붙은 위반을 던지도록 강제하고 결정적으로 고정한다 — 옛 코드는 {@code save} 를
 * 부르고 예외를 번역하지 않으므로 여기서 떨어진다.
 *
 * <p>컨테이너 없이 돈다.
 */
class StaffInvitationServiceTranslationTest {
    private final StaffInvitationRepository invitations = mock(StaffInvitationRepository.class);
    private final BoothRepository booths = mock(BoothRepository.class);
    private final BoothStaffRepository staffs = mock(BoothStaffRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StaffAccessGuard staffGuard = mock(StaffAccessGuard.class);
    private final StaffInvitationService service =
            new StaffInvitationService(invitations, booths, staffs, users, staffGuard);

    /** 같은 사람이 같은 초대를 동시에 두 번 수락했다 — 두 번째의 INSERT 가 PK 에 걸린다. */
    @Test
    void acceptTranslatesTheStaffPrimaryKeyViolationIntoAlreadyMember() {
        StaffInvitation invitation = new StaffInvitation(7L, 42L, 1L, StaffRole.CONSULTANT, Instant.now());
        when(invitations.findById(12L)).thenReturn(Optional.of(invitation));
        when(staffs.findRole(7L, 42L)).thenReturn(Optional.empty());
        when(staffs.saveAndFlush(any(BoothStaff.class))).thenThrow(violationOf("booth_staffs_pkey"));

        ApiException refused = assertThrows(ApiException.class, () -> service.accept(12L, 42L));

        assertEquals(ErrorCode.STAFF_ALREADY_MEMBER, refused.errorCode());
        verify(staffs).saveAndFlush(any(BoothStaff.class));
    }

    /** 같은 대상에게 같은 부스가 동시에 두 번 초대했다 — 두 번째가 부분 유니크 인덱스에 걸린다. */
    @Test
    void inviteTranslatesThePendingUniqueIndexViolationIntoInvitationPending() {
        Booth booth = mock(Booth.class);
        when(booth.getId()).thenReturn(7L);
        when(booth.getName()).thenReturn("부스");
        when(booth.isOwnedBy(42L)).thenReturn(false);
        User invitee = mock(User.class);
        when(invitee.getId()).thenReturn(42L);
        when(invitee.getNickname()).thenReturn("대상");
        when(staffGuard.requireStaffManager(7L, 1L)).thenReturn(booth);
        when(users.findByNickname("대상")).thenReturn(Optional.of(invitee));
        when(staffs.findRole(7L, 42L)).thenReturn(Optional.empty());
        when(invitations.findByBoothIdAndInvitedUserIdAndStatus(7L, 42L, StaffInvitationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(invitations.saveAndFlush(any(StaffInvitation.class)))
                .thenThrow(violationOf("ux_staff_invitations_pending"));

        ApiException refused = assertThrows(ApiException.class, () -> service.invite(7L, 1L,
                new StaffInvitationService.InviteCommand("대상", "CONSULTANT")));

        assertEquals(ErrorCode.STAFF_INVITATION_PENDING, refused.errorCode());
        verify(invitations).saveAndFlush(any(StaffInvitation.class));
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("위반",
                new ConstraintViolationException("위반", new SQLException("23505"), constraint));
    }
}
