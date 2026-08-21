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
 * A rentable position in the world (spec 004).
 *
 * <p>Deliberately carries no occupancy field. Whether a slot is taken is decided by the existence
 * of a valid active lease, so there is no second copy of that fact to drift out of sync
 * (research R-02). The {@code status} column is an operational switch — whether the slot is
 * offered at all — and is unrelated to the lease lifecycle.
 */
@Entity
@Table(name = "booth_slots")
public class BoothSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_code", nullable = false, unique = true, length = 30)
    private String slotCode;

    @Column(name = "floor_no", nullable = false)
    private short floorNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", nullable = false, length = 30)
    private SlotType slotType;

    @Column(nullable = false, length = 20)
    private String status = "AVAILABLE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BoothSlot() {
    }

    public Long getId() { return id; }
    public String getSlotCode() { return slotCode; }
    public short getFloorNo() { return floorNo; }
    public SlotType getSlotType() { return slotType; }
    public String getStatus() { return status; }

    /** Whether a member may lease this slot at all (spec 004 FR-002). */
    public boolean isRentable() {
        return slotType == SlotType.USER_RENTAL && "AVAILABLE".equals(status);
    }
}
