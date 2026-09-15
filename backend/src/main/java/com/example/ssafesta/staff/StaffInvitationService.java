package com.example.ssafesta.staff;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothStaffRepository;
import com.example.ssafesta.booth.StaffRole;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ConstraintViolations;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 초대의 생성·수락·취소 (spec 011 US3, FR-001·FR-015~FR-017).
 *
 * <p><b>닉네임으로 초대한다.</b> {@code docs/08} §10 은 {@code userId} 를 적었지만 Owner 가 남의
 * 숫자 id 를 알아낼 경로가 없다 — 사용자 검색 endpoint 가 develop 에 없고, 만들면 닉네임 훑기와
 * id 수집 표면이 함께 생긴다. {@code users.nickname} 이 V1 부터 {@code UNIQUE} 라 식별자로
 * 충분하고 {@code findByNickname} 도 이미 있다. 계약 문서는 이 구현과 함께 정정한다 (헌법 24조).
 */
@Service
public class StaffInvitationService {

    private final StaffInvitationRepository invitations;
    private final BoothRepository booths;
    private final BoothStaffRepository staffs;
    private final UserRepository users;
    private final StaffAccessGuard staffGuard;

    public StaffInvitationService(StaffInvitationRepository invitations, BoothRepository booths,
                                  BoothStaffRepository staffs, UserRepository users,
                                  StaffAccessGuard staffGuard) {
        this.invitations = invitations;
        this.booths = booths;
        this.staffs = staffs;
        this.users = users;
        this.staffGuard = staffGuard;
    }

    /**
     * Owner·{@code ADMIN} 이 닉네임으로 초대한다.
     *
     * <p>거절 순서가 계약이다 — 권한, 대상 존재, 이미 구성원인가, 이미 대기 중인가. 권한을 먼저
     * 보는 이유는 남의 부스에 대고 닉네임 존재 여부를 물을 수 없게 하기 위해서다.
     */
    @Transactional
    public InvitationView invite(Long boothId, Long inviterUserId, InviteCommand command) {
        Booth booth = staffGuard.requireStaffManager(boothId, inviterUserId);
        StaffRole role = parseRole(command);
        User invitee = users.findByNickname(requireNickname(command))
                .orElseThrow(() -> new ApiException(ErrorCode.STAFF_INVITEE_NOT_FOUND));

        requireNotAlreadyMember(booth, invitee.getId());
        invitations.findByBoothIdAndInvitedUserIdAndStatus(
                        boothId, invitee.getId(), StaffInvitationStatus.PENDING)
                .ifPresent(pending -> {
                    throw new ApiException(ErrorCode.STAFF_INVITATION_PENDING);
                });

        StaffInvitation saved;
        try {
            // flush 를 여기서 한다 — 위 조회를 함께 통과한 두 요청 중 진 쪽의 INSERT 가
            // ux_staff_invitations_pending 에 걸리는 자리를 이 메서드 안으로 당겨 409 로 번역한다.
            // 커밋 시점까지 미루면 그 위반은 컨트롤러 밖에서 500 이 된다 (S15P21A604-693).
            saved = invitations.saveAndFlush(
                    new StaffInvitation(boothId, invitee.getId(), inviterUserId, role, Instant.now()));
        } catch (DataIntegrityViolationException violation) {
            throw translateInvite(violation);
        }
        return InvitationView.of(saved, booth.getName(), invitee.getNickname());
    }

    /**
     * 본인에게 온 수락 가능한 초대만 (FR-016). 만료분은 스위퍼를 기다리지 않고 여기서 빠진다.
     *
     * <p>부스 이름을 함께 싣는다 — 받은 쪽 화면이 "어느 부스가 불렀는가" 없이는 수락 여부를
     * 정할 수 없고, 이름을 빼면 FE 가 초대 수만큼 부스를 다시 조회하게 된다.
     */
    @Transactional(readOnly = true)
    public List<InvitationView> findMine(Long userId) {
        List<StaffInvitation> mine = invitations.findAcceptableFor(userId, Instant.now());
        Map<Long, String> names = booths.findAllById(mine.stream().map(StaffInvitation::getBoothId).toList())
                .stream().collect(Collectors.toMap(Booth::getId, Booth::getName));
        return mine.stream()
                .map(invitation -> InvitationView.of(invitation, names.get(invitation.getBoothId()), null))
                .toList();
    }

    /**
     * 초대받은 본인이 수락한다 — {@code booth_staffs} 행이 여기서 처음 생긴다.
     *
     * <p>전이와 행 생성이 한 트랜잭션이다. 갈라 두면 행만 생기고 초대가 {@code PENDING} 으로 남는다.
     *
     * <p>같은 초대를 동시에 두 번 수락해도 자리는 하나다. 그 보장은 사전 조회가 아니라
     * {@code seatIfAbsent} 가 돌려주는 행 수에서 온다 — 두 요청이 사전 조회를 함께 통과할 수 있고,
     * 그때 진 쪽이 조용히 성공하던 것이 GitLab #190 이다.
     */
    @Transactional
    public void accept(Long invitationId, Long userId) {
        StaffInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new ApiException(ErrorCode.STAFF_INVITATION_NOT_FOUND));
        if (!invitation.getInvitedUserId().equals(userId)) {
            throw new ApiException(ErrorCode.STAFF_INVITATION_FORBIDDEN);
        }
        if (!invitation.isAcceptableAt(Instant.now())) {
            throw new ApiException(ErrorCode.STAFF_INVITATION_NOT_PENDING);
        }
        if (staffs.findRole(invitation.getBoothId(), userId).isPresent()) {
            // 흔한 경우를 한 번의 읽기로 거른다. 경합의 방어는 아래가 한다 — 이 조회는 두 요청이
            // 함께 통과할 수 있다.
            throw new ApiException(ErrorCode.STAFF_ALREADY_MEMBER);
        }
        // 자리를 잡은 쪽만 1 을 돌려받는다. 예외를 기다리지 않는 이유는 BoothStaffRepository#seatIfAbsent
        // 에 적어 두었다 — 조립키 엔티티라 save() 가 merge 를 타고, 진 쪽이 UPDATE 로 조용히
        // 성공해 버린다 (GitLab #190).
        if (staffs.seatIfAbsent(invitation.getBoothId(), userId, invitation.getRole().name()) == 0) {
            throw new ApiException(ErrorCode.STAFF_ALREADY_MEMBER);
        }
        invitation.accept(Instant.now());
    }

    /** 초대의 INSERT 가 깨뜨린 제약 — 부분 유니크 인덱스면 "대기 중 초대가 이미 있다". */
    static RuntimeException translateInvite(DataIntegrityViolationException violation) {
        if (ConstraintViolations.isViolationOf(violation, "ux_staff_invitations_pending")) {
            return new ApiException(ErrorCode.STAFF_INVITATION_PENDING);
        }
        return violation;
    }

    /** Owner·{@code ADMIN} 이 대기 중 초대를 거둔다 (FR-017). 초대받은 쪽의 거절 API 는 없다 (C-10). */
    @Transactional
    public void cancel(Long boothId, Long invitationId, Long actorUserId) {
        staffGuard.requireStaffManager(boothId, actorUserId);
        StaffInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new ApiException(ErrorCode.STAFF_INVITATION_NOT_FOUND));
        if (!invitation.getBoothId().equals(boothId)) {
            throw new ApiException(ErrorCode.STAFF_INVITATION_NOT_FOUND);
        }
        if (invitation.getStatus() != StaffInvitationStatus.PENDING) {
            throw new ApiException(ErrorCode.STAFF_INVITATION_NOT_PENDING);
        }
        invitation.cancel();
    }

    /** 스위퍼가 부르는 자리 — 트랜잭션 경계를 프록시로 타려고 서비스에 둔다. */
    @Transactional
    public int expireStale() {
        return invitations.expireStaleAsOf(Instant.now());
    }

    private void requireNotAlreadyMember(Booth booth, Long inviteeUserId) {
        if (booth.isOwnedBy(inviteeUserId) || staffs.findRole(booth.getId(), inviteeUserId).isPresent()) {
            throw new ApiException(ErrorCode.STAFF_ALREADY_MEMBER);
        }
    }

    private static String requireNickname(InviteCommand command) {
        if (command == null || command.nickname() == null || command.nickname().isBlank()) {
            throw ApiException.fieldInvalid("nickname", "초대할 회원의 닉네임이 필요합니다.");
        }
        return command.nickname().trim();
    }

    private static StaffRole parseRole(InviteCommand command) {
        if (command == null || command.role() == null) {
            throw ApiException.fieldInvalid("role", "역할이 필요합니다.");
        }
        return StaffRole.from(command.role())
                .orElseThrow(() -> ApiException.fieldInvalid("role",
                        "역할은 ADMIN, CONTENT_EDITOR, CONSULTANT 중 하나입니다."));
    }

    /** 초대 요청 본문. {@code userId} 가 아니라 {@code nickname} 인 이유는 클래스 주석에 있다. */
    public record InviteCommand(String nickname, String role) { }

    public record InvitationView(Long invitationId, Long boothId, String boothName, String nickname,
                                 String role, Instant expiresAt) {

        static InvitationView of(StaffInvitation invitation, String boothName, String nickname) {
            return new InvitationView(invitation.getId(), invitation.getBoothId(), boothName, nickname,
                    invitation.getRole().name(), invitation.getExpiresAt());
        }
    }
}
