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
    private final BoothLeaseRepository leases;
    private final BoothLayoutDraftRepository drafts;
    private final BoothLayoutPublishedVersionRepository published;
    private final BoothEditorGuard editorGuard;

    public BoothLayoutQueryService(BoothRepository booths, BoothLeaseRepository leases,
                                   BoothLayoutDraftRepository drafts,
                                   BoothLayoutPublishedVersionRepository published,
                                   BoothEditorGuard editorGuard) {
        this.booths = booths;
        this.leases = leases;
        this.drafts = drafts;
        this.published = published;
        this.editorGuard = editorGuard;
    }

    /**
     * The editor's working copy, or empty when the booth has never been edited.
     *
     * <p>Deliberately does <b>not</b> check the lease: an owner whose lease has expired must still
     * be able to open their content, which is what FR-011's "보존" means in practice.
     */
    @Transactional(readOnly = true)
    public Optional<DraftView> findDraft(Long boothId, Long userId) {
        Booth booth = editorGuard.requireEditor(boothId, userId);
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

        // The expiry predicate lives in one place — spec 004's repository. A second copy here would
        // be the fourth place it is written, and the one that eventually forgets the time condition
        // (research R-06).
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));

        Integer version = booth.getPublishedLayoutVersion();
        if (version == null) {
            throw new LayoutNotPublishedException();
        }
        BoothLayoutPublishedVersion snapshot = published.findByBoothIdAndVersionNo(boothId, version)
                .orElseThrow(LayoutNotPublishedException::new);
        return PublishedView.of(snapshot);
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
