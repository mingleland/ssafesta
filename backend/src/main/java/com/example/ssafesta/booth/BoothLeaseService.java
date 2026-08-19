package com.example.ssafesta.booth;

import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates booth leases (spec 004 User Story 1 and 2).
 *
 * <p><b>The whole point of this class is that one transaction covers both the lease row and the
 * coin charge.</b> {@link WalletService} joins the caller's transaction (REQUIRED propagation), so
 * if anything below fails — including the unique index rejecting a concurrent lease — the charge
 * rolls back with it. "Coins gone, no booth" is not handled by a compensating refund here; it is
 * made unrepresentable (FR-003, SC-002).
 */
@Service
public class BoothLeaseService {

    private static final Logger log = LoggerFactory.getLogger(BoothLeaseService.class);
    private static final int ALLOWED_DURATION_DAYS = 1;
    static final String LEASE_REASON = "LEASE_PAYMENT";
    static final String LEASE_REFERENCE_TYPE = "BOOTH_LEASE";

    private final BoothSlotRepository slots;
    private final BoothRepository booths;
    private final BoothLeaseRepository leases;
    private final WalletService wallets;
    private final LeaseProperties properties;

    public BoothLeaseService(BoothSlotRepository slots, BoothRepository booths, BoothLeaseRepository leases,
                             WalletService wallets, LeaseProperties properties) {
        this.slots = slots;
        this.booths = booths;
        this.leases = leases;
        this.wallets = wallets;
        this.properties = properties;
    }

    /**
     * Leases a slot to a member.
     *
     * @param durationDays must be 1 — P0 has no extension and a fixed 24h term (D02, D05)
     * @return the created lease, or the caller's existing one when they already hold this slot
     */
    @Transactional
    public LeaseOutcome lease(Long userId, Long slotId, int durationDays) {
        if (durationDays != ALLOWED_DURATION_DAYS) {
            throw new IllegalArgumentException("임대 기간은 1일만 가능합니다.");
        }
        Instant now = Instant.now();

        BoothSlot slot = slots.findById(slotId).orElseThrow(() -> new SlotNotFoundException(slotId));
        if (!slot.isRentable()) {
            throw new SlotNotRentableException(slotId);
        }

        Optional<BoothLease> holder = leases.findValidBySlotId(slotId, now);
        if (holder.isPresent()) {
            BoothLease existing = holder.get();
            if (existing.getLesseeUserId().equals(userId)) {
                // A retried request from the tenant themselves. Return what they already have
                // rather than charging again (FR-018).
                return new LeaseOutcome(existing, wallets.balanceOf(userId), true);
            }
            throw new SlotAlreadyLeasedException(slotId);
        }

        leases.findValidByLesseeUserId(userId, now).ifPresent(active -> {
            throw new ActiveLeaseLimitException(active.getId());
        });

        releaseStaleLeases(slotId, now);

        Booth booth = ownBooth(userId);
        booth.attachSlot(slotId);

        BoothLease lease;
        try {
            lease = leases.saveAndFlush(new BoothLease(booth.getId(), slotId, userId, now,
                    properties.duration(), properties.priceCoin()));
        } catch (DataIntegrityViolationException exception) {
            // Lost the race on ux_booth_leases_active_slot (or on booths.current_slot_id). The
            // transaction is doomed, which is exactly what this case needs: the losing request
            // must leave nothing behind — no lease and no coin charge (SC-002).
            log.info("동시 임대 경합에서 밀렸습니다 — userId={}, slotId={}", userId, slotId);
            throw new SlotAlreadyLeasedException(slotId);
        }

        LedgerResult payment = charge(userId, lease);
        log.info("부스 임대 — userId={}, slotId={}, leaseId={}, coin={}, endsAt={}",
                userId, slotId, lease.getId(), properties.priceCoin(), lease.getEndsAt());
        return new LeaseOutcome(lease, payment.balanceAfter(), false);
    }

    /**
     * Charges the lease fee through the ledger (헌법 20조).
     *
     * <p>The idempotency key uses the lease id so that a later lease of the same slot by the same
     * member gets a different key. Keying on (user, slot) would silently skip the second charge —
     * a free booth (research R-04).
     */
    private LedgerResult charge(Long userId, BoothLease lease) {
        return wallets.spend(new CoinSpendCommand(userId, lease.getChargedCoin(), LEASE_REASON,
                LEASE_REFERENCE_TYPE, String.valueOf(lease.getId()),
                LEASE_REASON + ":" + LEASE_REFERENCE_TYPE + ":" + lease.getId()));
    }

    /**
     * Transitions leases that are still {@code ACTIVE} but whose time has passed, and detaches the
     * booth that held the slot.
     *
     * <p>Without this a slot can never be leased again: {@code ux_booth_leases_active_slot} counts
     * a stale row as active, and {@code booths.current_slot_id} is UNIQUE (FR-017, D05).
     */
    private void releaseStaleLeases(Long slotId, Instant now) {
        List<BoothLease> stale = leases.findStaleActiveBySlotId(slotId, now);
        for (BoothLease lease : stale) {
            lease.expire();
            booths.findById(lease.getBoothId()).ifPresent(Booth::detachSlot);
            log.info("만료 임대 정리 — leaseId={}, slotId={}, boothId={}",
                    lease.getId(), slotId, lease.getBoothId());
        }
        // A booth may still point at this slot even without a stale lease row (e.g. data repaired
        // by hand). Detach it too, or the UNIQUE column blocks the new booth.
        booths.findByCurrentSlotId(slotId).ifPresent(Booth::detachSlot);
        leases.flush();
        booths.flush();
    }

    /**
     * The member's own booth, created on first lease.
     *
     * <p>Never returns another member's booth: that is what stops a new tenant from inheriting the
     * previous owner's layout, documents and survey answers (C-01, invariant I-5, SC-004).
     */
    private Booth ownBooth(Long userId) {
        return booths.findByOwnerUserId(userId)
                .orElseGet(() -> booths.save(new Booth(userId, "내 부스")));
    }

    /** Result of a lease request. {@code alreadyHeld} marks the idempotent retry path (FR-018). */
    public record LeaseOutcome(BoothLease lease, int balanceAfter, boolean alreadyHeld) {
    }
}
