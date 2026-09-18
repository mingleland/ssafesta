package com.example.ssafesta.consultation;

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
 * 방문자가 사람 상담을 청한 것, 그리고 그것이 수락되면 그 세션 (spec 011 US1).
 *
 * <p><b>요청과 세션이 한 행이다.</b> 확정 계약의 경로가 `requests/{requestId}/accept` 와
 * `sessions/{sessionId}/end` 로 갈려 있지만 둘은 같은 값이다 — 행을 나누면 "정확히 한 명만
 * 수락"(SC-001) 판정이 두 표에 걸치고 상태 전이도 두 곳에 생긴다 (research R-06).
 *
 * <p><b>만료는 읽는 쪽이 판정한다.</b> 스위퍼가 뒤늦게 {@code EXPIRED} 로 옮기지만 그 사이에
 * 도착한 수락은 {@link #isAcceptableAt(Instant)} 가 막는다 — 스위퍼가 멈춰도 10분이 스케줄러
 * 주기만큼 늘어나지 않는다. spec 004 임대가 {@code ACTIVE and ends_at > now} 를 정본으로 둔 것과
 * 같은 판단이다.
 */
@Entity
@Table(name = "consultations")
public class Consultation {

    /** C-01 — 계약값이다. 운영 손잡이가 아니라서 설정으로 빼지 않는다. */
    public static final Duration REQUEST_VALID_FOR = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false)
    private Long boothId;

    @Column(name = "visitor_user_id")
    private Long visitorUserId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "staff_user_id")
    private Long staffUserId;

    @Column(name = "ai_conversation_id", length = 100)
    private String aiConversationId;

    /**
     * 요청 생성 시점의 AI 대화 요약 스냅샷 (FR-008).
     *
     * <p>이후 대화가 이어져도 갱신하지 않는다 — 직원이 본 요약이 나중에 달라지면 안 된다.
     * 요약이 없거나 생성이 실패하면 {@code null} 이고, 그것이 요청을 막지 않는다 (헌법 3조).
     */
    @Column(name = "summary")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConsultationStatus status = ConsultationStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected Consultation() {
    }

    public Consultation(Long boothId, Long visitorUserId, Long agentId, String aiConversationId,
                        String summary, Instant now) {
        this.boothId = boothId;
        this.visitorUserId = visitorUserId;
        this.agentId = agentId;
        this.aiConversationId = aiConversationId;
        this.summary = summary;
        this.status = ConsultationStatus.REQUESTED;
        this.requestedAt = now;
    }

    public Long getId() { return id; }

    public Long getBoothId() { return boothId; }

    public Long getVisitorUserId() { return visitorUserId; }

    public Long getStaffUserId() { return staffUserId; }

    public String getSummary() { return summary; }

    public ConsultationStatus getStatus() { return status; }

    public Instant getRequestedAt() { return requestedAt; }

    /** 만료까지 남은 초. 음수가 되지 않게 0 에서 멈춘다 — FE 가 잔여 시간을 그대로 표시한다. */
    public long remainingSecondsAt(Instant now) {
        long remaining = Duration.between(now, requestedAt.plus(REQUEST_VALID_FOR)).toSeconds();
        return Math.max(remaining, 0);
    }

    /** 대기 중이고 아직 시간이 남았다 — 두 조건을 한 자리에서 본다 (C-01). */
    public boolean isAcceptableAt(Instant now) {
        return status == ConsultationStatus.REQUESTED && now.isBefore(requestedAt.plus(REQUEST_VALID_FOR));
    }

    /** 방문자가 대기 중 요청을 거둔다. 만료와 가르는 이유는 {@link ConsultationStatus#CANCELLED}. */
    void cancel(Instant now) {
        this.status = ConsultationStatus.CANCELLED;
        this.endedAt = now;
    }

    /** 방문자·직원 누구나 끝낼 수 있다 (FR-010) — 누가 끝냈는지는 이벤트가 전한다. */
    void end(Instant now) {
        this.status = ConsultationStatus.ENDED;
        this.endedAt = now;
    }
}
