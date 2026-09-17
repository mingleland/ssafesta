package com.example.ssafesta.booth;

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
 * One rental of one slot (spec 004).
 *
 * <p>{@code endsAt} is computed here from the payment instant and the configured duration, never
 * taken from the request (D02, invariant I-4, 헌법 16조).
 *
 * <p>A lease only ever leaves {@code ACTIVE}: by running out of time ({@link #expire()}) or by the
 * tenant handing it back ({@link #cancel()}, D12). Neither is reversible and a re-lease creates a
 * new row.
 *
 * <p>Slot exclusivity is enforced by a partial unique index created in V1:
 * {@code CREATE UNIQUE INDEX ux_booth_leases_active_slot ON booth_leases(slot_id) WHERE status = 'ACTIVE'}.
 * That index does not look at {@code endsAt}, which is why an expired lease must still be
 * transitioned to {@code EXPIRED} before the slot can be leased again (FR-017).
 */
@Entity
@Table(name = "booth_leases")
public class BoothLease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    @Column(name = "slot_id", nullable = false, updatable = false)
    private Long slotId;

    @Column(name = "lessee_user_id", nullable = false, updatable = false)
    private Long lesseeUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaseStatus status = LeaseStatus.ACTIVE;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    /** When the D07 one-hour warning was claimed. Null means it has not been sent yet. */
    @Column(name = "expiry_warning_sent_at")
    private Instant expiryWarningSentAt;

    @Column(name = "charged_coin", nullable = false, updatable = false)
    private int chargedCoin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BoothLease() {
    }

    public BoothLease(Long boothId, Long slotId, Long lesseeUserId, Instant startsAt, Duration duration,
                      int chargedCoin) {
        this.boothId = boothId;
        this.slotId = slotId;
        this.lesseeUserId = lesseeUserId;
        this.startsAt = startsAt;
        this.endsAt = startsAt.plus(duration);
        this.chargedCoin = chargedCoin;
    }

    public Long getId() { return id; }
    public Long getBoothId() { return boothId; }
    public Long getSlotId() { return slotId; }
    public Long getLesseeUserId() { return lesseeUserId; }
    public LeaseStatus getStatus() { return status; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getExpiryWarningSentAt() { return expiryWarningSentAt; }
    public int getChargedCoin() { return chargedCoin; }

    /** Claims the one-time expiry warning before its WebSocket event is published after commit. */
    void markExpiryWarningSent(Instant sentAt) {
        this.expiryWarningSentAt = sentAt;
    }

    /** Marks a lease whose time has passed as expired, freeing the slot for re-lease (FR-017). */
    void expire() {
        this.status = LeaseStatus.EXPIRED;
    }

    /**
     * Marks a lease the tenant handed back early, freeing the slot right away (D12, FR-020).
     *
     * <p>A different word from {@link #expire()} so the history says which one happened; everything
     * else about the release is identical, including <b>not</b> refunding the coin (FR-021).
     */
    void cancel() {
        this.status = LeaseStatus.CANCELLED;
    }

    /** Seconds left, or 0 once expired (spec 004 FR-007, SC-005). */
    public long remainingSecondsAt(Instant moment) {
        long remaining = Duration.between(moment, endsAt).toSeconds();
        return Math.max(remaining, 0);
    }
}
