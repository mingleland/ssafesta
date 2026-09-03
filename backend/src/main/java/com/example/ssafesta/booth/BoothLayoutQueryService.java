package com.example.ssafesta.booth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of booth layouts (spec 005 FR-005, FR-006, FR-015).
 *
 * <p>The two reads are deliberately different shapes of access, not one query with a flag:
 *
 * <ul>
 *   <li>the draft is reachable only by an editor, and only from the draft table;
 *   <li>the published layout is reachable by anyone, and only from the published table.
 * </ul>
 *
 * <p>That separation <i>is</i> invariant I-4 — there is no code path by which a visitor's request
 * can end up reading {@code booth_layout_drafts}, so "작업 중인 것이 보였다" cannot happen through a
 * forgotten condition (SC-003).
 */
@Service
public class BoothLayoutQueryService {

    private final BoothRepository booths;
    private final BoothSlotRepository slots;
    private final BoothLeaseRepository leases;
    private final BoothLayoutDraftRepository drafts;
    private final BoothLayoutPublishedVersionRepository published;
    private final BoothAccessGuard accessGuard;

    public BoothLayoutQueryService(BoothRepository booths, BoothSlotRepository slots,
                                   BoothLeaseRepository leases,
                                   BoothLayoutDraftRepository drafts,
                                   BoothLayoutPublishedVersionRepository published,
                                   BoothAccessGuard accessGuard) {
        this.booths = booths;
        this.slots = slots;
        this.leases = leases;
        this.drafts = drafts;
        this.published = published;
        this.accessGuard = accessGuard;
    }

    /**
     * The editor's working copy, or empty when the booth has never been edited.
     *
     * <p>Deliberately does <b>not</b> check the lease: an owner whose lease has expired must still
     * be able to open their content, which is what FR-011's "보존" means in practice.
     */
    @Transactional(readOnly = true)
    public Optional<DraftView> findDraft(Long boothId, Long userId) {
        Booth booth = accessGuard.requireEditor(boothId, userId);
        return drafts.findById(boothId).map(draft -> DraftView.of(draft, booth));
    }

    /**
     * What a visitor — and Unity — sees.
     *
     * @throws BoothExpiredException        the lease is not valid right now (FR-015, invariant I-5)
     * @throws LayoutNotPublishedException  nothing is published for this booth
     */
    @Transactional(readOnly = true)
    public PublishedView findPublished(Long boothId) {
        Booth booth = booths.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));

        // The expiry predicate lives in one place — the guard. A second copy here would be the
        // one that eventually forgets the time condition (research R-06). No editor check: this is
        // the visitor's path.
        accessGuard.requireActiveLease(boothId);

        Integer version = booth.getPublishedLayoutVersion();
        if (version == null) {
            throw new LayoutNotPublishedException();
        }
        BoothLayoutPublishedVersion snapshot = published.findByBoothIdAndVersionNo(boothId, version)
                .orElseThrow(LayoutNotPublishedException::new);
        return PublishedView.of(snapshot);
    }

    /**
     * The same layout, asked for by <b>room</b> rather than by booth (#62, contract §11).
     *
     * <p>Unity's anchors are slots, not booths. A slot outlives the booths that pass through it,
     * so the caller cannot resolve {@code boothId} itself: after a re-lease the same room holds a
     * different one, and calling {@link #findPublished} with an anchor number would draw
     * <b>someone else's</b> booth with no error to notice (#62 §1). The resolution therefore lives
     * here, on the side that owns the lease table.
     *
     * <p>An empty slot and an unpublished booth deliberately answer the same 404: both mean
     * "nothing to build here", which is what Unity's graceful skip already does. Only an expired
     * lease is distinguished, because the visitor is owed the reason (FR-015).
     *
     * @throws SlotNotFoundException        no such slot
     * @throws BoothExpiredException        the room's lease has run out (409)
     * @throws LayoutNotPublishedException  the room is empty, or its booth has published nothing
     */
    @Transactional(readOnly = true)
    public PublishedView findPublishedBySlot(Long slotId) {
        if (!slots.existsById(slotId)) {
            throw new SlotNotFoundException(slotId);
        }
        Instant now = Instant.now();

        Optional<BoothLease> valid = leases.findValidBySlotId(slotId, now);
        if (valid.isPresent()) {
            // Delegate rather than re-query: FR-015's expiry check and the version lookup stay
            // written once, and §5 and §11 cannot drift into serving different bodies.
            return findPublished(valid.get().getBoothId());
        }

        // A lease row still marked ACTIVE with its time passed is an expiry, not an empty room —
        // the sweep that flips it to EXPIRED runs on the next lease attempt, not on a clock
        // (FR-017). Reading it as "empty" would tell a visitor the booth never existed.
        List<BoothLease> stale = leases.findStaleActiveBySlotId(slotId, now);
        if (!stale.isEmpty()) {
            throw new BoothExpiredException(stale.get(0).getBoothId());
        }
        throw new LayoutNotPublishedException();
    }

    /** Editor-facing draft. Carries {@code publishedVersion} so the editor can show "공개본과 다름". */
    public record DraftView(Long boothId, long revision, Integer schemaVersion, String template,
                            List<LayoutJson.LayoutObject> objects, Instant updatedAt,
                            Long updatedByUserId, Integer publishedVersion) {

        static DraftView of(BoothLayoutDraft draft, Booth booth) {
            LayoutJson.LayoutDocument document = LayoutJson.parse(draft.getLayoutJson()).document();
            return new DraftView(draft.getBoothId(), draft.getRevision(), document.schemaVersion(),
                    document.template(), document.objects(), draft.getUpdatedAt(),
                    draft.getUpdatedByUserId(), booth.getPublishedLayoutVersion());
        }
    }

    /**
     * Visitor-facing snapshot. Field names match Unity's {@code BoothLayoutDto} —
     * {@code version} is the <b>publish count</b>, {@code schemaVersion} the structure (R-10).
     */
    public record PublishedView(Long boothId, int version, Integer schemaVersion, String template,
                                List<LayoutJson.LayoutObject> objects) {

        static PublishedView of(BoothLayoutPublishedVersion snapshot) {
            LayoutJson.LayoutDocument document = LayoutJson.parse(snapshot.getLayoutJson()).document();
            return new PublishedView(snapshot.getBoothId(), snapshot.getVersionNo(),
                    document.schemaVersion(), document.template(), document.objects());
        }
    }
}
