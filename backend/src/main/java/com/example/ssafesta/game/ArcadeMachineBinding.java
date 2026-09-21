package com.example.ssafesta.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Which published game one arcade machine runs (S15P21A604-602, GitLab #56 안 1).
 *
 * <p>The key is the machine, not a surrogate: one machine runs exactly one game (2026-09-10 배치
 * 결정), so {@code machine_id} carries that rule by itself. A surrogate id plus a unique constraint
 * would state the same fact twice.
 *
 * <p>{@code machineId} is the <b>scene's</b> canonical id, not something the server hands out.
 * Unity knows it from the prefab it placed and never learns {@code gameId} — that split is what
 * lets curation change without a Unity rebuild (spec 019 FR-017).
 *
 * <p>No booth here. These machines are world fixtures, so there is no lease and no owner to check,
 * which is why the resolution response has no {@code boothId} and cannot answer
 * {@code BOOTH_LEASE_EXPIRED}. The booth-interior {@code GAME_PORTAL} path is a different table and
 * is on hold (S15P21A604-158·-204).
 */
@Entity
@Table(name = "arcade_machine_bindings")
public class ArcadeMachineBinding {

    @Id
    @Column(name = "machine_id", nullable = false, updatable = false, length = 64)
    private String machineId;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    /**
     * 자리를 잡은 사람. {@code null} 이면 운영자가 걸어 둔 고정물이다 (V45).
     *
     * <p>한도 계산과 해제가 이 값을 본다 — 사용자는 운영자 기계를 회수할 수 없고, 운영자 기계는
     * 누구의 2대에도 들어가지 않는다. 게임 소유자를 타고 세지 않는 것은 게임이 양도되면 셈이
     * 흔들리기 때문이다. 자리는 게임이 아니라 잡은 사람에게 묶인다.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ArcadeMachineBinding() {
    }

    /** 운영자 시드용 — 주인이 없는 고정물이다. */
    public ArcadeMachineBinding(String machineId, Long gameId) {
        this(machineId, gameId, null);
    }

    public ArcadeMachineBinding(String machineId, Long gameId, Long ownerUserId) {
        this.machineId = machineId;
        this.gameId = gameId;
        this.ownerUserId = ownerUserId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getMachineId() {
        return machineId;
    }

    public Long getGameId() {
        return gameId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
