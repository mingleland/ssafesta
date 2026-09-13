package com.example.ssafesta.staff;

import com.example.ssafesta.booth.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * 부스가 회원에게 보낸 직원 초대 (spec 011 FR-001·FR-015, US3).
 *
 * <p>테이블은 {@code V1__initial_schema.sql} 부터 있었고 {@code expires_at} 컬럼도 그때부터
 * 있었다 — 비어 있던 것은 그 값을 정하는 규칙뿐이다. C-07 이 <b>48시간</b>으로 닫았다.
 *
 * <p><b>만료는 읽는 쪽이 판정한다.</b> 스위퍼가 뒤늦게 {@code EXPIRED} 로 옮기지만, 그 사이에
 * 도착한 수락은 {@link #isAcceptableAt(Instant)} 가 막는다 — spec 004 임대가 {@code status =
 * ACTIVE AND ends_at > now} 를 정본으로 둔 것과 같은 판단이다. 멈춘 스케줄러가 만료된 초대를
 * 살려 두지 못한다.
 */
@Entity
@Table(name = "staff_invitations")
public class StaffInvitation {

    /** C-07 — 계약값이다. 운영 손잡이가 아니라서 설정으로 빼지 않는다. */
    public static final Duration VALID_FOR = Duration.ofHours(48);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false)
    private Long boothId;

    @Column(name = "invited_user_id", nullable = false)
    private Long invitedUserId;

    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private StaffRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StaffInvitationStatus status = StaffInvitationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected StaffInvitation() {
    }

    public StaffInvitation(Long boothId, Long invitedUserId, Long invitedByUserId, StaffRole role,
                           Instant now) {
        this.boothId = boothId;
        this.invitedUserId = invitedUserId;
        this.invitedByUserId = invitedByUserId;
        this.role = role;
        this.status = StaffInvitationStatus.PENDING;
        this.createdAt = now;
        this.expiresAt = now.plus(VALID_FOR);
    }

    public Long getId() { return id; }

    public Long getBoothId() { return boothId; }

    public Long getInvitedUserId() { return invitedUserId; }

    public StaffRole getRole() { return role; }

    public StaffInvitationStatus getStatus() { return status; }

    public Instant getExpiresAt() { return expiresAt; }

    /**
     * 대기 중이고 아직 시간이 남았다 — 두 조건을 한 자리에서 본다.
     *
     * <p>스위퍼가 아직 안 돌아 {@code PENDING} 으로 남아 있는 만료분을 수락 경로가 통과시키면
     * "48시간" 이 스케줄러 주기만큼 늘어난다.
     */
    public boolean isAcceptableAt(Instant now) {
        return status == StaffInvitationStatus.PENDING && now.isBefore(expiresAt);
    }

    void accept(Instant now) {
        this.status = StaffInvitationStatus.ACCEPTED;
        this.acceptedAt = now;
    }

    void cancel() {
        this.status = StaffInvitationStatus.CANCELLED;
    }

    void expire() {
        this.status = StaffInvitationStatus.EXPIRED;
    }
}
