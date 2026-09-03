package com.example.ssafesta.booth;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two questions every booth path has to ask first: <b>may this member edit it</b> (spec 005
 * FR-012) and <b>is it still leased</b> (spec 004 만료 계약).
 *
 * <p>The editor check was here from the start. Spreading those two lines across three services is
 * how one of them eventually forgets the staff branch, or worse, forgets the owner check entirely.
 *
 * <p>Expiry joined for the same reason, but after it had already happened: the lease check had
 * been copied into eight services, three of them behind a private {@code requireValidLease}, and a
 * new path had nothing to inherit it from. The predicate itself still belongs to spec 004's
 * repository — this class only decides <b>who has to pass it</b>.
 *
 * <p><b>Access, not editing.</b> The name says so because three callers are visitor reads with no
 * editor in sight ({@code BoothQueryService.findPublicBooth}, {@code
 * BoothLayoutQueryService.findPublished}, {@code ProjectService.requireVisitorVisible}) — expiry
 * blocks the visitor too, and a guard named for editors would have read as the wrong check there.
 */
@Component
public class BoothAccessGuard {

    private final BoothRepository booths;
    private final BoothStaffRepository staffs;
    private final BoothLeaseRepository leases;

    public BoothAccessGuard(BoothRepository booths, BoothStaffRepository staffs,
                            BoothLeaseRepository leases) {
        this.booths = booths;
        this.staffs = staffs;
        this.leases = leases;
    }

    /**
     * @return the booth, so callers do not load it a second time
     * @throws BoothNotFoundException        no such booth
     * @throws BoothEditorForbiddenException the member is neither owner nor staff
     */
    @Transactional(readOnly = true)
    public Booth requireEditor(Long boothId, Long userId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
        if (!booth.isOwnedBy(userId) && !staffs.existsByBoothIdAndUserId(boothId, userId)) {
            throw new BoothEditorForbiddenException();
        }
        return booth;
    }

    /**
     * The booth is leased <b>right now</b> — no permission question asked (spec 004 만료 계약).
     *
     * <p>Blocks writes and visitor reads alike. An expired booth shows nothing to anyone, so editing
     * one would be changing something invisible, and a visitor is owed the reason rather than a
     * booth that quietly renders as if nothing ended (FR-015, FR-019, invariant I-5).
     *
     * <p>The one deliberate exception is the <b>editor's</b> read: an owner whose lease has expired
     * must still be able to open their own content, which is what FR-011's "보존" means in practice.
     * Those paths call {@link #requireEditor} alone — see {@code BoothLayoutQueryService.findDraft}.
     *
     * @return the valid lease, so callers that need its slot or end date do not query twice
     * @throws BoothExpiredException the booth has no lease valid at this instant
     */
    @Transactional(readOnly = true)
    public BoothLease requireActiveLease(Long boothId) {
        return leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));
    }

    /**
     * Editor <b>and</b> unexpired — the pair nine call sites were writing on two adjacent lines.
     *
     * <p>Permission first, availability second: a member with no claim on this booth should be told
     * they cannot edit it, not that its lease ran out. The order is what the callers had, and it is
     * the one that does not leak whether a booth someone has no business with is still alive.
     *
     * @return the booth, so callers do not load it a second time
     */
    @Transactional(readOnly = true)
    public Booth requireActiveEditor(Long boothId, Long userId) {
        Booth booth = requireEditor(boothId, userId);
        requireActiveLease(boothId);
        return booth;
    }
}
