package com.example.ssafesta.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 한 사람이 한 오락기에서 낸 최고점 (S15P21A604-963, GitLab #264).
 *
 * <p><b>랭킹은 게임기의 것이다.</b> 키에 {@code gameId} 가 없다 — 오락실 캐비닛은 사용자가
 * 게시할 때마다 주인이 바뀌므로(V45, {@link ArcadeSeatService}) 게임이 교체돼도 이전 게임에서
 * 딴 점수가 같은 순위표에 남는다. 2026-09-22 결정이며, 초기화가 필요해지면 지점은
 * {@code ArcadeSeatService.claimOnPublish} 하나다.
 *
 * <p><b>표시 전용이다.</b> 점수는 클라이언트 신고값 그대로이고 서버가 검증할 수단이 없다 —
 * 조작 가능성을 감수한 대신 이 값은 Coin·Reward·Inventory 어디에도 닿지 않는다 (spec 019
 * FR-021 예외, contracts §Arcade Ranking).
 *
 * <p>쓰기는 이 엔티티를 거치지 않는다. "더 높은 점수만 갱신" 은
 * {@link ArcadeScoreRecordRepository#upsertIfHigher} 의 한 문장이 판정한다 — 읽고 나서 쓰면 그
 * 사이에 같은 사람의 다른 요청이 낀다.
 */
@Entity
@Table(name = "arcade_score_records")
@IdClass(ArcadeScoreRecord.Key.class)
public class ArcadeScoreRecord {

    @Id
    @Column(name = "machine_id", nullable = false, updatable = false, length = 64)
    private String machineId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "best_score", nullable = false)
    private int bestScore;

    @Column(name = "achieved_at", nullable = false)
    private Instant achievedAt;

    protected ArcadeScoreRecord() {
    }

    public String getMachineId() {
        return machineId;
    }

    public Long getUserId() {
        return userId;
    }

    public int getBestScore() {
        return bestScore;
    }

    public Instant getAchievedAt() {
        return achievedAt;
    }

    /** 복합 기본키. 표가 "사용자당 오락기별 1건" 을 스스로 지킨다. */
    public static class Key implements Serializable {

        private String machineId;
        private Long userId;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(machineId, key.machineId) && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(machineId, userId);
        }
    }
}
