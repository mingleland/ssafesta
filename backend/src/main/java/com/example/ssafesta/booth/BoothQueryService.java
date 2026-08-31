package com.example.ssafesta.booth;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of booths and slots (spec 004 FR-001, FR-007).
 *
 * <p>Every occupancy answer here goes through {@link BoothLeaseRepository}'s validity queries, so
 * a lease whose time has passed reads as free even before anyone transitions it. That is what
 * keeps an expired booth out of the world without a scheduler (SC-003, research R-03).
 */
@Service
public class BoothQueryService {

    private final BoothSlotRepository slots;
    private final BoothRepository booths;
    private final BoothLeaseRepository leases;

    public BoothQueryService(BoothSlotRepository slots, BoothRepository booths, BoothLeaseRepository leases) {
        this.slots = slots;
        this.booths = booths;
        this.leases = leases;
    }

    /**
     * All slots with their current occupancy.
     *
     * @param viewerUserId the requester, or {@code null} for an unauthenticated view
     */
    @Transactional(readOnly = true)
    public List<SlotView> listSlots(Long viewerUserId) {
        Instant now = Instant.now();
        Map<Long, BoothLease> bySlot = new HashMap<>();
        for (BoothLease lease : leases.findAllValid(now)) {
            bySlot.put(lease.getSlotId(), lease);
        }

        return slots.findAllOrdered().stream().map(slot -> {
            BoothLease lease = bySlot.get(slot.getId());
            if (lease == null) {
                return SlotView.available(slot);
            }
            String boothName = booths.findById(lease.getBoothId()).map(Booth::getName).orElse(null);
            boolean mine = viewerUserId != null && viewerUserId.equals(lease.getLesseeUserId());
            return SlotView.occupied(slot, lease, boothName, mine, now);
        }).toList();
    }

    /** The member's own booth and its lease, or empty when they have never leased (FR-007). */
    @Transactional(readOnly = true)
    public Optional<MyBoothView> findMyBooth(Long userId) {
        Instant now = Instant.now();
        return booths.findByOwnerUserId(userId).map(booth -> {
            BoothLease lease = leases.findValidByBoothId(booth.getId(), now).orElse(null);
            BoothSlot slot = lease == null ? null : slots.findById(lease.getSlotId()).orElse(null);
            return MyBoothView.of(booth, lease, slot, now);
        });
    }

    /**
     * A visitor's view of a booth.
     *
     * @throws BoothExpiredException when the lease has ended — expiry is not pushed to the world,
     *         so this refusal is how the visitor learns about it (FR-019)
     */
    @Transactional(readOnly = true)
    public PublicBoothView findPublicBooth(Long boothId) {
        Instant now = Instant.now();
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        BoothLease lease = leases.findValidByBoothId(boothId, now)
                .orElseThrow(() -> new BoothExpiredException(boothId));
        return new PublicBoothView(booth.getId(), lease.getSlotId(), booth.getName(),
                lease.getStatus().name(), true, lease.getEndsAt(),
                BoothFacadeService.FacadeView.of(booth), booth.getPublishedLayoutVersion(),
                visibleHomepageUrl(booth));
    }

    /**
     * "Only while the booth is public" (spec 016 FR-003), read as <b>only while a published layout
     * exists</b>.
     *
     * <p>The laptop is an object inside the published layout, so that predicate lines up exactly
     * with the moment a visitor could click it — there is no window where the URL is readable but
     * unreachable. An unregistered URL is {@code null} too, which lets the overlay decide
     * "unregistered or unpublished" from one value instead of two (FR-009, research R-05).
     *
     * <p>Expiry needs no branch here: {@link #findPublicBooth} has already refused with
     * {@code BOOTH_LEASE_EXPIRED} by this point.
     */
    private String visibleHomepageUrl(Booth booth) {
        return booth.isPublished() ? booth.getHomepageUrl() : null;
    }

    public record SlotView(Long slotId, String slotCode, short floorNo, String type, String status,
                           Long boothId, String boothName, Instant leaseEndsAt, Long remainingSeconds,
                           boolean entryAvailable, boolean mine) {

        static SlotView available(BoothSlot slot) {
            return new SlotView(slot.getId(), slot.getSlotCode(), slot.getFloorNo(), slot.getSlotType().name(),
                    "AVAILABLE", null, null, null, null, false, false);
        }

        static SlotView occupied(BoothSlot slot, BoothLease lease, String boothName, boolean mine, Instant now) {
            return new SlotView(slot.getId(), slot.getSlotCode(), slot.getFloorNo(), slot.getSlotType().name(),
                    "OCCUPIED", lease.getBoothId(), boothName, lease.getEndsAt(),
                    lease.remainingSecondsAt(now), true, mine);
        }
    }

    /**
     * @param homepageUrl the stored value, <b>never gated</b> — unlike {@link PublicBoothView}. The
     *        owner of an unpublished booth still has to see their own URL to edit it, and this
     *        surface is already theirs alone (spec 016 research R-06)
     */
    public record MyBoothView(Long boothId, String name, String status, LeaseView lease,
                              String homepageUrl) {

        static MyBoothView of(Booth booth, BoothLease lease, BoothSlot slot, Instant now) {
            // The booth stays even when the lease is over — its content is preserved (FR-010).
            return new MyBoothView(booth.getId(), booth.getName(),
                    lease == null ? BoothStatus.INACTIVE.name() : BoothStatus.ACTIVE.name(),
                    lease == null ? null : LeaseView.of(lease, slot, now),
                    booth.getHomepageUrl());
        }
    }

    public record LeaseView(Long leaseId, Long slotId, String slotCode, Instant startsAt, Instant endsAt,
                            long remainingSeconds, int chargedCoin) {

        static LeaseView of(BoothLease lease, BoothSlot slot, Instant now) {
            return new LeaseView(lease.getId(), lease.getSlotId(), slot == null ? null : slot.getSlotCode(),
                    lease.getStartsAt(), lease.getEndsAt(), lease.remainingSecondsAt(now), lease.getChargedCoin());
        }
    }

    /**
     * What docs/08 §3 promised all along. {@code facade} and {@code publishedLayoutVersion} were in
     * the documented contract before spec 005; they are only now backed by columns (V8·V9).
     */
    public record PublicBoothView(Long boothId, Long slotId, String name, String leaseStatus,
                                  boolean entryAvailable, Instant endsAt,
                                  BoothFacadeService.FacadeView facade, Integer publishedLayoutVersion,
                                  String homepageUrl) {
    }
}
