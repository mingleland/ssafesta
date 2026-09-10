package com.example.ssafesta.minigame;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One minigame play (spec 014 FR-002, FR-004).
 *
 * <p>The table has been in {@code V1__initial_schema.sql:156} since the first migration and no Java
 * ever used it; this class is the first reader. Nothing is added to it — the columns already carry
 * everything the contract needs, which is why S15P21A604-502 ships no migration.
 *
 * <p><b>{@code nonce} is the external handle.</b> It goes on the wire as {@code sessionId} and the
 * bigint {@code id} never leaves the server. That is what makes its {@code UNIQUE} constraint earn
 * its keep, and it keeps play counts and other members' sessions unguessable.
 *
 * <p><b>{@code targetValue}/{@code resultValue} are {@link BigDecimal}, not {@code double}.</b> The
 * columns are {@code NUMERIC(8,3)} and {@code spring.jpa.hibernate.ddl-auto} is {@code validate},
 * so a floating-point field fails the context, not a test.
 *
 * <p>{@code rewardLedgerEntryId} is the second guard on paying once — the idempotency key on
 * {@code WalletService.credit} is the first, and this column is {@code UNIQUE}, so a second ledger
 * entry could not be attached here even if one were written. Same shape as
 * {@code survey/SurveyResponse}.
 */
@Entity
@Table(name = "minigame_sessions")
public class MinigameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "game_type", nullable = false, updatable = false, length = 30)
    private String gameType;

    /** The value clients know this session by. Server-issued (spec 014 C-06). */
    @Column(name = "nonce", nullable = false, updatable = false)
    private UUID nonce;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MinigameSessionStatus status;

    /** The target time this player must stop on, issued by the server (FR-001a). */
    @Column(name = "target_value", nullable = false, updatable = false, precision = 8, scale = 3)
    private BigDecimal targetValue;

    /** The reported stop time. Stored even when the claim was rejected, so it can be looked at. */
    @Column(name = "result_value", precision = 8, scale = 3)
    private BigDecimal resultValue;

    @Column(name = "reward_coin", nullable = false)
    private int rewardCoin;

    @Column(name = "reward_ledger_entry_id")
    private Long rewardLedgerEntryId;

    /**
     * When the server issued this session. The whole anti-cheat check is measured from here, so it
     * is the server's clock and never anything the client sends.
     */
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected MinigameSession() {
    }

    private MinigameSession(Long userId, String gameType, UUID nonce, BigDecimal targetValue, Instant now) {
        this.userId = userId;
        this.gameType = gameType;
        this.nonce = nonce;
        this.status = MinigameSessionStatus.IN_PROGRESS;
        this.targetValue = targetValue;
        this.rewardCoin = 0;
        this.startedAt = now;
    }

    public static MinigameSession start(Long userId, String gameType, UUID nonce,
                                        BigDecimal targetValue, Instant now) {
        return new MinigameSession(userId, gameType, nonce, targetValue, now);
    }

    /** Judged and accepted — a timeout is also accepted, it just pays nothing. */
    public void complete(BigDecimal resultValue, int rewardCoin, Instant now) {
        transitionTo(MinigameSessionStatus.COMPLETED, resultValue, now);
        this.rewardCoin = rewardCoin;
    }

    /** The reported stop time did not agree with the server's elapsed time (FR-008). */
    public void reject(BigDecimal resultValue, Instant now) {
        transitionTo(MinigameSessionStatus.REJECTED, resultValue, now);
    }

    private void transitionTo(MinigameSessionStatus next, BigDecimal resultValue, Instant now) {
        // A terminal session is replayed by the caller, never re-judged. Reaching here would mean
        // the wallet lock that serializes one member's submissions did not hold, and paying twice
        // is the failure that follows — so say so rather than quietly overwrite the first verdict.
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "이미 끝난 미니게임 세션을 다시 판정할 수 없습니다: nonce=" + nonce + ", status=" + status);
        }
        this.status = next;
        this.resultValue = resultValue;
        this.completedAt = now;
    }

    /** Called only after {@code WalletService.credit} returned an entry id. */
    public void linkReward(Long ledgerEntryId) {
        this.rewardLedgerEntryId = ledgerEntryId;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getGameType() {
        return gameType;
    }

    public UUID getNonce() {
        return nonce;
    }

    public MinigameSessionStatus getStatus() {
        return status;
    }

    public BigDecimal getTargetValue() {
        return targetValue;
    }

    public BigDecimal getResultValue() {
        return resultValue;
    }

    public int getRewardCoin() {
        return rewardCoin;
    }

    public Long getRewardLedgerEntryId() {
        return rewardLedgerEntryId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
