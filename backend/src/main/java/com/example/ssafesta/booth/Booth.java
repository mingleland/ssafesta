package com.example.ssafesta.booth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A member's booth and the container its content hangs off (spec 004).
 *
 * <p><b>A booth belongs to its owner, not to a slot</b> (spec 004 C-01). The slot is only a
 * position it occupies while a lease is active. That is what makes it impossible for a later
 * tenant of the same slot to inherit the previous owner's layout, documents or survey responses:
 * they get their own booth, and the old one keeps its content under its original owner
 * (invariant I-5, SC-004).
 *
 * <p>Expiry therefore never deletes a booth — it only detaches the slot (FR-010).
 */
@Entity
@Table(name = "booths")
public class Booth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    /**
     * The slot this booth currently occupies, or {@code null} when not leased.
     *
     * <p>The column is UNIQUE, so a stale value blocks anyone else from taking that slot — the
     * detach in {@link #detachSlot()} is what keeps re-leasing possible (FR-017).
     */
    @Column(name = "current_slot_id")
    private Long currentSlotId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "facade_code", nullable = false, length = 50)
    private String facadeCode = "DEFAULT";

    @Column(name = "homepage_url", length = 2048)
    private String homepageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BoothStatus status = BoothStatus.INACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Booth() {
    }

    public Booth(Long ownerUserId, String name) {
        this.ownerUserId = ownerUserId;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public Long getCurrentSlotId() { return currentSlotId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BoothStatus getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }

    void attachSlot(Long slotId) {
        this.currentSlotId = slotId;
        this.status = BoothStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /** Releases the slot while keeping every piece of content intact (spec 004 FR-010). */
    void detachSlot() {
        this.currentSlotId = null;
        this.status = BoothStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public boolean isOwnedBy(Long userId) {
        return ownerUserId.equals(userId);
    }
}
