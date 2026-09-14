package com.example.ssafesta.booth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 한 번의 부스 방문 (S15P21A604-240, GitLab #94).
 *
 * <p>계측이 배포 <b>전에</b> 심겨 있어야 하는 이유는 간단하다 — "Coin 시스템 유효성은 배포 후
 * 사용자 반응으로 검증하라" 는 피드백을 실행하려면 그 반응을 기록할 자리가 먼저 있어야 한다.
 * 배포 후에 붙이면 첫 사용자들의 행동이 남지 않는다.
 *
 * <p>표는 {@code V1__initial_schema.sql} 부터 있었다. 비어 있던 것은 쓰는 코드뿐이다.
 *
 * <p><b>게스트도 센다.</b> {@code visitor_user_id} 가 nullable 인 것이 그 뜻이다 — 부스 구경은
 * 게스트에게 열려 있고(헌법 12조가 막는 것은 소유·결제다), 방문 통계에서 그들을 빼면 실제
 * 트래픽을 절반만 보게 된다.
 *
 * <p><b>닫히지 않은 방문이 정상이다.</b> 브라우저를 그냥 닫으면 퇴장 신호가 오지 않는다. 그래서
 * {@code exited_at} 이 {@code null} 인 행을 오류로 다루지 않고, 평균 체류 계산에서 빼고 그 수를
 * 따로 보고한다 — 임의의 timeout 으로 닫으면 그 숫자가 실제 체류가 아니라 우리가 고른 상수가 된다.
 */
@Entity
@Table(name = "booth_visit_events")
public class BoothVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false)
    private Long boothId;

    /** 게스트·익명은 {@code null} 이다. */
    @Column(name = "visitor_user_id")
    private Long visitorUserId;

    @Column(name = "world_channel", nullable = false, length = 50)
    private String worldChannel;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt = Instant.now();

    @Column(name = "exited_at")
    private Instant exitedAt;

    protected BoothVisit() {
    }

    public BoothVisit(Long boothId, Long visitorUserId, String worldChannel, Instant enteredAt) {
        this.boothId = boothId;
        this.visitorUserId = visitorUserId;
        this.worldChannel = worldChannel;
        this.enteredAt = enteredAt;
    }

    public Long getId() { return id; }

    public Long getBoothId() { return boothId; }

    public Long getVisitorUserId() { return visitorUserId; }

    public Instant getEnteredAt() { return enteredAt; }

    public Instant getExitedAt() { return exitedAt; }

    public boolean isOpen() {
        return exitedAt == null;
    }

    /** 두 번째 퇴장 신호는 무시한다 — 첫 신호가 실제 퇴장에 가깝다. */
    void exit(Instant now) {
        if (exitedAt == null) {
            this.exitedAt = now;
        }
    }
}
