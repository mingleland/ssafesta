package com.example.ssafesta.staff;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 직원 초대 조회·만료 (spec 011 FR-015~FR-017). */
public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, Long> {

    /**
     * 본인에게 온 <b>수락 가능한</b> 초대 (FR-016, C-08).
     *
     * <p>스위퍼가 아직 안 옮긴 만료분을 목록에서 빼는 것이 여기 조건의 이유다 — 화면에 떠 있는데
     * 누르면 409 가 나는 초대를 보여 주지 않는다.
     */
    @Query("""
            select i from StaffInvitation i
            where i.invitedUserId = :userId
              and i.status = com.example.ssafesta.staff.StaffInvitationStatus.PENDING
              and i.expiresAt > :now
            order by i.expiresAt
            """)
    List<StaffInvitation> findAcceptableFor(@Param("userId") Long userId, @Param("now") Instant now);

    /** 부스가 그 회원에게 이미 보낸 대기 중 초대 — {@code ux_staff_invitations_pending} 과 같은 질문. */
    Optional<StaffInvitation> findByBoothIdAndInvitedUserIdAndStatus(
            Long boothId, Long invitedUserId, StaffInvitationStatus status);

    /**
     * 기한이 지난 대기분을 {@code EXPIRED} 로 (C-07, research R-07).
     *
     * <p>벌크 갱신이다 — 한 건씩 읽어 고칠 이유가 없고, 수락 경로는 이 배치를 기다리지 않는다
     * ({@link StaffInvitation#isAcceptableAt}).
     *
     * @return 옮긴 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StaffInvitation i
            set i.status = com.example.ssafesta.staff.StaffInvitationStatus.EXPIRED
            where i.status = com.example.ssafesta.staff.StaffInvitationStatus.PENDING
              and i.expiresAt <= :now
            """)
    int expireStaleAsOf(@Param("now") Instant now);
}
