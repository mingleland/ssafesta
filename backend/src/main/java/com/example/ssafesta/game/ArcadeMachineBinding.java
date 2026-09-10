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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ArcadeMachineBinding() {
    }

    public ArcadeMachineBinding(String machineId, Long gameId) {
        this.machineId = machineId;
        this.gameId = gameId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getMachineId() {
        return machineId;
    }

    public Long getGameId() {
        return gameId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
