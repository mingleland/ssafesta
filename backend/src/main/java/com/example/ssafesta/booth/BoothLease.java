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
    public int getChargedCoin() { return chargedCoin; }

    /** Marks a lease whose time has passed as expired, freeing the slot for re-lease (FR-017). */
    void expire() {
        this.status = LeaseStatus.EXPIRED;
    }

    /** Seconds left, or 0 once expired (spec 004 FR-007, SC-005). */
    public long remainingSecondsAt(Instant moment) {
        long remaining = Duration.between(moment, endsAt).toSeconds();
        return Math.max(remaining, 0);
    }
}
