package com.example.ssafesta.booth;

import com.example.ssafesta.ai.BoothDocumentDeactivationService;
import com.example.ssafesta.common.ConstraintViolations;
import com.example.ssafesta.user.AdminActionRecorder;
import com.example.ssafesta.user.AdminGuard;
import com.example.ssafesta.wallet.CoinReason;
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
 *
 * <p>It also owns the other direction — {@link #expireStaleLeases()}, {@link #cancel} and the
 * private {@code release} every path shares — because ending a lease is the same two rows in the
 * same transaction (S15P21A604-152), plus the booth's AI documents since S15P21A604-496.
 * Expiry and the tenant's early return (D12) differ only in the word written into the row, so
 * they go through one method rather than two that could drift apart.
 */
@Service
public class BoothLeaseService {

    private static final Logger log = LoggerFactory.getLogger(BoothLeaseService.class);
    private static final int ALLOWED_DURATION_DAYS = 1;
    static final String LEASE_REASON = CoinReason.LEASE_PAYMENT;
    static final String LEASE_REFERENCE_TYPE = CoinReason.LEASE_REFERENCE_TYPE;
    /** V6, lower case: PostgreSQL reports index names folded. */
    private static final String ACTIVE_LESSEE_INDEX = "ux_booth_leases_active_lessee";
    /** How many leases one sweeper transaction takes — see {@link BoothLeaseRepository#findStaleActive}. */
    private static final int EXPIRY_BATCH = 5;

    private final BoothSlotRepository slots;
    private final BoothRepository booths;
    private final BoothLeaseRepository leases;
    private final WalletService wallets;
    private final BoothDocumentDeactivationService aiDocuments;
    private final AdminGuard admins;
    private final AdminBoothPurger purger;
    private final AdminActionRecorder adminActions;
    private final LeaseProperties properties;

    public BoothLeaseService(BoothSlotRepository slots, BoothRepository booths, BoothLeaseRepository leases,
                             WalletService wallets, BoothDocumentDeactivationService aiDocuments,
                             AdminGuard admins, AdminBoothPurger purger,
                             AdminActionRecorder adminActions, LeaseProperties properties) {
        this.slots = slots;
        this.booths = booths;
        this.leases = leases;
        this.wallets = wallets;
        this.aiDocuments = aiDocuments;
        this.admins = admins;
        this.purger = purger;
        this.adminActions = adminActions;
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

        // Serialize this member's own concurrent requests before reading anything they are about
        // to write. Every check below is read-then-write, and seven parallel clicks on seven
        // different slots each read "no active lease" and each got one — a member ended up with
        // seven booths and paid seven times (T-110). ux_booth_leases_active_lessee (V6) is the
        // hard stop; this lock is what turns the race into a queue, so the second request gets a
        // clean ACTIVE_LEASE_LIMIT instead of a rolled-back constraint violation.
        wallets.lockOwner(userId);

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

        // An administrator leases free and forever, and holds one booth per slot (S15P21A604-905).
        // Read once: the three branches below must agree, and isAdmin() is a row read.
        boolean admin = admins.isAdmin(userId);

        if (!admin) {
            leases.findValidByLesseeUserId(userId, now).ifPresent(active -> {
                throw new ActiveLeaseLimitException(active.getId());
            });
        }

        releaseStaleLeases(slotId, now);
        if (!admin) {
            // Nothing of an administrator's is ever stale — their leases end in 2099 — and the
            // helper reads one Optional per member, which several permanent rows would break.
            releaseStaleLeasesOfMember(userId, now);
        }

        Booth booth = admin ? newAdminBooth(userId, slot) : ownBooth(userId);
        booth.attachSlot(slotId);

        BoothLease lease;
        try {
            lease = leases.saveAndFlush(admin
                    ? BoothLease.permanent(booth.getId(), slotId, userId, now, properties.adminEndsAt())
                    : new BoothLease(booth.getId(), slotId, userId, now,
                            properties.duration(), properties.priceCoin()));
        } catch (DataIntegrityViolationException exception) {
            // Lost a race on one of the partial unique indexes (or on booths.current_slot_id). The
            // transaction is doomed, which is exactly what this case needs: the losing request
            // must leave nothing behind — no lease and no coin charge (SC-002).
            throw translateRace(exception, userId, slotId);
        }

        if (admin) {
            // No ledger row at all, not a zero-coin one: a 0 Coin LEASE_PAYMENT would show up in
            // the administrator's own wallet history as a transaction that never happened.
            log.info("관리자 부스 임대 — userId={}, slotId={}, leaseId={}, 무상·영구, endsAt={}",
                    userId, slotId, lease.getId(), lease.getEndsAt());
            return new LeaseOutcome(lease, wallets.balanceOf(userId), false);
        }

        LedgerResult payment = charge(userId, lease);
        log.info("부스 임대 — userId={}, slotId={}, leaseId={}, coin={}, endsAt={}",
                userId, slotId, lease.getId(), properties.priceCoin(), lease.getEndsAt());
        return new LeaseOutcome(lease, payment.balanceAfter(), false);
    }


    /**
     * Hands the member's lease back before its time is up, freeing the slot at once (spec 004 D12,
     * FR-020).
     *
     * <p><b>No refund.</b> The wallet is not touched here at all: D06 refuses a change-of-mind
     * refund and FR-021 carries that into the early return, so a re-lease is a fresh charge. That is
     * also what keeps this from being abusable — the slot is free, the coin is spent.
     *
     * <p>Everything else is the expiry path. The slot release, the booth detach and the AI document
     * transitions all go through {@link #release}, so the only thing an early return changes is the
     * word written into the row.
     *
     * <p><b>Two steps, not one, and the order matters.</b> The lease row is locked first with no
     * time predicate, and only then is validity re-read. Binding an instant into the locking query
     * could not work — that query is how the lock is taken, and a bound parameter would not refresh
     * while the statement waits. So the lock comes first, {@code now} is read after it, and the
     * expiry rule stays where {@link BoothLeaseRepository} promises it lives rather than being
     * re-implemented here as a field comparison.
     *
     * <p>Against the sweeper both orderings are defined: whoever locks the row first decides it, and
     * a return that arrives after an expiry has committed is refused rather than overwriting it.
     *
     * @param slotId the slot the caller believes they are returning, and since S15P21A604-905 the
     *               key this method looks up. It was already required of members so a stale screen
     *               naming the wrong slot would be refused rather than tearing down whichever booth
     *               they happen to hold; an administrator holds several at once, so it is now the
     *               only thing that says which one. A member's outcome is unchanged — the lessee is
     *               checked right after, and naming someone else's slot is refused the same way.
     */
    @Transactional
    public void cancel(Long userId, Long slotId) {
        // Same serialization as lease(): without it a return and a re-lease can interleave so that
        // the re-lease reads "no active lease" before the return commits, and the member ends up
        // holding two.
        wallets.lockOwner(userId);

        BoothLease lease = lockValidLease(slotId);

        if (!lease.getLesseeUserId().equals(userId)) {
            log.info("반납 요청의 슬롯을 그 회원이 보유하고 있지 않습니다 — userId={}, 요청 slotId={}, 보유자={}",
                    userId, slotId, lease.getLesseeUserId());
            throw new ActiveLeaseNotFoundException();
        }

        release(lease, LeaseStatus.CANCELLED);
    }

    /**
     * Takes the slot's active lease under lock, or refuses (S15P21A604-927).
     *
     * <p><b>Two steps, and the order matters</b> — the reasoning is {@link #cancel}'s, which is why
     * both ending paths reach it here rather than each writing it out. The row is locked with no
     * time predicate first, and validity is re-read only after the lock is held: binding an instant
     * into the locking query could not work, because that query <i>is</i> how the lock is taken and
     * a bound parameter would not refresh while the statement waits.
     *
     * <p>A row that is logically expired by the time we read it is left to the sweeper. These paths
     * own returns, not expiries.
     */
    private BoothLease lockValidLease(Long slotId) {
        if (leases.findActiveBySlotIdForUpdate(slotId).isEmpty()) {
            log.info("반납할 임대가 없습니다 — slotId={}", slotId);
            throw new ActiveLeaseNotFoundException();
        }
        return leases.findValidBySlotId(slotId, Instant.now())
                .orElseThrow(() -> {
                    log.info("반납하려던 임대가 이미 만료 시각을 지났습니다 — slotId={}", slotId);
                    return new ActiveLeaseNotFoundException();
                });
    }

    /**
     * An administrator takes a slot back from whoever holds it (S15P21A604-927).
     *
     * <p><b>Reached through {@code POST /admin/booths/{boothId}/unpublish}</b>, not through a URL of
     * its own. 강제 비공개 is the one administrator action on a booth, and a console that had to call
     * a second endpoint to finish the same intent is how the original report happened — the booth was
     * hidden and the seat stayed taken. {@link AdminBoothPublicationService} resolves the booth's
     * slot and calls this.
     *
     * <p><b>It is the tenant's return, performed by someone else.</b> Nothing about the ending
     * differs: {@link #release} writes {@code CANCELLED}, detaches the slot and deactivates the AI
     * documents in this transaction, and the member's booth and all its content are preserved
     * exactly as spec 004 FR-010 requires. What the administrator takes away is the seat, not the
     * work — re-leasing continues from the same content, Draft-first (D08). An administrator's own
     * booth still goes through {@link #releaseAdminBooth}, because that is what its own return does.
     *
     * <p>So the only thing this adds to {@link #cancel} is who may ask: the lessee check is replaced
     * by an administrator check, and an audit row records who took the seat and why.
     *
     * <p><b>No refund</b> (spec 004 D06 extended to this path). The wallet is not touched.
     *
     * <p>Locking is {@link #cancel}'s, one indirection further out: the lessee is the person whose
     * concurrent requests have to queue, and they are only known once the lease is read. So the
     * lease is peeked at first, the lessee's row is locked, and only then is the lease taken under
     * lock — same order as everywhere else (wallet, then lease), which is what keeps the two paths
     * from deadlocking against each other.
     *
     * @param actorUserId the administrator, recorded as the actor
     * @param slotId      the slot to take back
     * @param reason      why, in their words — the audit column that makes the row readable later
     */
    @Transactional
    public void releaseByAdmin(Long actorUserId, Long slotId, String reason) {
        admins.requireAdmin(actorUserId);
        if (slots.findById(slotId).isEmpty()) {
            throw new SlotNotFoundException(slotId);
        }

        Long lessee = leases.findValidBySlotId(slotId, Instant.now())
                .map(BoothLease::getLesseeUserId)
                .orElseThrow(() -> {
                    log.info("강제 반납할 임대가 없습니다 — actorUserId={}, slotId={}", actorUserId, slotId);
                    return new ActiveLeaseNotFoundException();
                });
        wallets.lockOwner(lessee);

        BoothLease lease = lockValidLease(slotId);
        if (!lease.getLesseeUserId().equals(lessee)) {
            // 잠금을 잡는 사이에 임대가 갈렸다 — 훑어본 임대가 끝나고 다른 회원이 같은 자리를 빌렸다.
            // 잠근 지갑이 그 사람의 것이 아니므로 여기서 회수하면 직렬화가 성립하지 않는다. 화면이
            // 낡았다는 뜻이고, 다시 읽고 다시 요청하면 된다.
            log.info("강제 반납 대상 임대가 잠금 사이에 바뀌었습니다 — slotId={}, 훑어본 임차인={}, 현재 임차인={}",
                    slotId, lessee, lease.getLesseeUserId());
            throw new ActiveLeaseNotFoundException();
        }
        Booth booth = booths.findById(lease.getBoothId())
                .orElseThrow(() -> new BoothNotFoundException(lease.getBoothId()));
        // 마스터의 부스를 관리자가 빼앗는 것은 막는다 — AdminGuard 의 보호가 자원을 통해서도
        // 성립해야 한다는 같은 이유다. 관리자 부스는 예외다: 그 부스는 사람이 아니라 권한을
        // 따라가므로(S15P21A604-905), 설치자가 마스터라는 이유로 막으면 운영 부스가 한 사람에게
        // 묶인다 — AdminBoothPublicationService.unpublish 와 같은 예외다 (규칙이 갈리면 편집은
        // 되는데 회수는 안 되는 상태가 된다).
        if (!booth.isAdminOwned()) {
            admins.requireOwnerNotMaster(booth.getOwnerUserId());
        }

        // 감사 행이 먼저다. 같은 트랜잭션(Propagation.MANDATORY)이라 함께 커밋되거나 함께 사라지고,
        // 관리자 부스는 이 다음 줄에서 삭제되므로 그 뒤에 쓰면 target 으로 남길 부스가 이미 없다.
        adminActions.record(actorUserId, AdminActionRecorder.BOOTH_LEASE_RELEASE,
                AdminActionRecorder.TARGET_BOOTH, booth.getId(), reason);

        log.info("관리자 강제 반납 — actorUserId={}, slotId={}, boothId={}, 임차인={}, 사유={}",
                actorUserId, slotId, booth.getId(), lessee, reason);
        release(lease, LeaseStatus.CANCELLED);
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
        for (BoothLease lease : leases.findStaleActiveBySlotId(slotId, now)) {
            release(lease, LeaseStatus.EXPIRED);
        }
        // A booth may still point at this slot even without a stale lease row (e.g. data repaired
        // by hand). Detach it too, or the UNIQUE column blocks the new booth.
        booths.findByCurrentSlotId(slotId).ifPresent(Booth::detachSlot);
        leases.flush();
        booths.flush();
    }

    /**
     * Expires stale leases wherever they sit, in one transaction — the sweeper's entry point
     * (S15P21A604-152, docs/08 "Lease 만료 처리 계약").
     *
     * <p><b>This does not make the batch authoritative.</b> Validity is still decided when a lease
     * is read ({@code status = ACTIVE AND ends_at > now}, spec 004 C-02), so a stopped scheduler
     * cannot let an expired booth be entered. What the pass adds is the DB state itself: without it
     * the transition waits for the next re-lease of that slot or member, so an expired booth keeps
     * its {@code current_slot_id} — and its published layout with it — until someone happens to
     * lease again.
     *
     * <p>The transaction covers the transition, the slot release and — since S15P21A604-496 — the
     * booth's AI document and Job transitions, which spec 007 FR-041 requires to be in the same
     * transaction as the expiry.
     *
     * @return how many leases this pass transitioned
     */
    @Transactional
    public int expireStaleLeases() {
        List<BoothLease> stale = leases.findStaleActive(Instant.now(), EXPIRY_BATCH);
        for (BoothLease lease : stale) {
            release(lease, LeaseStatus.EXPIRED);
        }
        return stale.size();
    }

    /**
     * One lease's expiry, for every path that expires one — the lazy paths above and the sweeper.
     *
     * <p>Both halves belong together (docs/08): a lease that is {@code EXPIRED} while its booth
     * still points at the slot leaves the slot unleasable, and a detached booth whose lease is still
     * {@code ACTIVE} occupies the member's one-lease limit.
     *
     * <p><b>The booth is detached only if it still points at this lease's slot.</b> The sweeper runs
     * against rows nobody asked about, so it can meet a booth that has already moved on: a member
     * whose lease on slot A expired and who has since leased slot B has one booth pointing at B, and
     * an unconditional detach here would null out that pointer — and, through
     * {@link Booth#detachSlot}, the published layout version of a booth with a perfectly valid
     * lease. The lazy paths are unaffected by the guard: they run before {@code attachSlot}, so the
     * booth still points at the slot being released.
     */
    private void release(BoothLease lease, LeaseStatus to) {
        Booth booth = booths.findById(lease.getBoothId()).orElse(null);
        if (booth != null && booth.isAdminOwned()) {
            releaseAdminBooth(lease, booth);
            return;
        }

        boolean cancelled = to == LeaseStatus.CANCELLED;
        if (cancelled) {
            lease.cancel();
        } else {
            lease.expire();
        }
        if (booth != null && lease.getSlotId().equals(booth.getCurrentSlotId())) {
            booth.detachSlot();
        }
        // The AI half of the same release (spec 007 FR-015·FR-041, S15P21A604-496). It is here rather
        // than in each caller because spec.md:71 wants it in this transaction, and because a second
        // place to end a lease is a second place to forget this.
        aiDocuments.deactivate(lease.getBoothId(), cancelled
                ? BoothDocumentDeactivationService.Cause.CANCELLED
                : BoothDocumentDeactivationService.Cause.EXPIRED);
        // The expiry wording is asserted verbatim by BoothLeaseExpirySweeperIntegrationTest, which
        // proves the lazy path and the sweeper pass share one method. Keep it exact.
        log.info(cancelled
                        ? "임대 반납 정리 — leaseId={}, slotId={}, boothId={}"
                        : "만료 임대 정리 — leaseId={}, slotId={}, boothId={}",
                lease.getId(), lease.getSlotId(), lease.getBoothId());
    }

    /**
     * An administrator's booth does not survive its lease — it is deleted, content and all
     * (S15P21A604-905).
     *
     * <p><b>Why the member path does not apply here.</b> That path preserves everything (FR-010) and
     * detaches the slot, because a member keeps one booth across leases and re-leasing continues
     * their own content. An administrator gets a <i>new</i> booth for every slot, so the returned one
     * has no future: leaving it behind piles up dormant booths that no screen can reach and no member
     * owns. Transitioning its status or deactivating its AI documents would be bookkeeping on rows
     * that are about to be gone.
     *
     * <p><b>The order is forced.</b> Content first through {@link AdminBoothPurger} (plain JDBC,
     * fifteen foreign keys deep), then the lease and the booth through JPA so the persistence
     * context knows they are gone. The flush before the purge is what stops Hibernate from writing
     * this transaction's pending changes onto rows the purge has already deleted.
     *
     * <p><b>This destroys visitor-generated rows</b> — survey responses, visit metrics and
     * consultation transcripts left in that booth. It is inherent to the booth disappearing: none of
     * those foreign keys cascade, so a delete that spared them could not run at all.
     */
    private void releaseAdminBooth(BoothLease lease, Booth booth) {
        Long boothId = booth.getId();
        booth.detachSlot();
        leases.flush();
        booths.flush();

        purger.purge(boothId);
        leases.delete(lease);
        booths.delete(booth);
        leases.flush();
        booths.flush();

        log.info("관리자 부스 반납 — leaseId={}, slotId={}, boothId={} (부스 삭제)",
                lease.getId(), lease.getSlotId(), boothId);
    }

    /**
     * Transitions the member's own leases that are still {@code ACTIVE} but whose time has passed,
     * wherever they sit.
     *
     * <p>{@code ux_booth_leases_active_lessee} (V6) does not look at {@code ends_at}, so without
     * this a member who once leased a slot could never lease again — the same trap the slot index
     * set, one table column over (FR-017).
     */
    private void releaseStaleLeasesOfMember(Long userId, Instant now) {
        for (BoothLease lease : leases.findStaleActiveByLesseeUserId(userId, now)) {
            release(lease, LeaseStatus.EXPIRED);
        }
        leases.flush();
        booths.flush();
    }

    /**
     * Names the race that was lost, so the caller hears the accurate reason.
     *
     * <p>"Someone else took the slot" and "you already hold a booth" are different problems with
     * different fixes for the member, and after V6 either index can be the one that fires.
     */
    private RuntimeException translateRace(DataIntegrityViolationException exception, Long userId, Long slotId) {
        String constraint = ConstraintViolations.nameOf(exception);
        if (constraint != null && constraint.toLowerCase().contains(ACTIVE_LESSEE_INDEX)) {
            log.info("동시 임대 경합 — 이미 임대를 보유한 회원입니다, userId={}, slotId={}", userId, slotId);
            return new ActiveLeaseLimitException(null);
        }
        log.info("동시 임대 경합에서 밀렸습니다 — userId={}, slotId={}, constraint={}", userId, slotId, constraint);
        return new SlotAlreadyLeasedException(slotId);
    }

    /**
     * The member's own booth, created on first lease.
     *
     * <p>Never returns another member's booth: that is what stops a new tenant from inheriting the
     * previous owner's layout, documents and survey answers (C-01, invariant I-5, SC-004).
     */
    private Booth ownBooth(Long userId) {
        return booths.findByOwnerUserIdAndAdminOwnedFalse(userId)
                .orElseGet(() -> booths.save(new Booth(userId, "내 부스")));
    }

    /**
     * A fresh booth for each slot an administrator takes (S15P21A604-905).
     *
     * <p>Not {@link #ownBooth}: that one is the member rule — one booth carried across leases — and
     * reusing it would make a second slot steal the first one's {@code current_slot_id}. The slot
     * code goes into the name so the several booths are told apart in the slot list without anyone
     * renaming them.
     *
     * <p>Nothing accumulates: a returned administrator booth is deleted outright, content and all
     * (see {@link #releaseAdminBooth}), so there is never a dormant one to reuse or to choose
     * between.
     */
    private Booth newAdminBooth(Long userId, BoothSlot slot) {
        return booths.save(new Booth(userId, "관리자 부스 " + slot.getSlotCode(), true));
    }

    /** Result of a lease request. {@code alreadyHeld} marks the idempotent retry path (FR-018). */
    public record LeaseOutcome(BoothLease lease, int balanceAfter, boolean alreadyHeld) {
    }
}
