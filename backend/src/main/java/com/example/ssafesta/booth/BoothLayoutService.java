package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiErrorDetail;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes to booth layouts: saving a draft and publishing one (spec 005 US1).
 *
 * <p>Publishing <b>copies</b> the draft into a new version row and moves the booth's pointer, both
 * in one transaction. Splitting them would allow a version that exists but that nobody can see, or
 * a pointer to a version that was never written (data-model §4) — the same reasoning that put coin
 * spending and lease creation in one transaction in spec 004.
 */
@Service
public class BoothLayoutService {

    private final BoothLayoutDraftRepository drafts;
    private final BoothLayoutPublishedVersionRepository published;
    private final BoothLeaseRepository leases;
    private final BoothEditorGuard editorGuard;
    private final LayoutValidator validator;
    private final BoothRepository booths;

    public BoothLayoutService(BoothLayoutDraftRepository drafts,
                              BoothLayoutPublishedVersionRepository published,
                              BoothLeaseRepository leases, BoothEditorGuard editorGuard,
                              LayoutValidator validator, BoothRepository booths) {
        this.drafts = drafts;
        this.published = published;
        this.leases = leases;
        this.editorGuard = editorGuard;
        this.validator = validator;
        this.booths = booths;
    }

    /**
     * Stores the working copy. Never publishes anything (FR-005, FR-006).
     *
     * @throws LayoutValidationFailedException  the layout breaks a structural rule
     * @throws LayoutRevisionConflictException  someone saved first (FR-014)
     */
    @Transactional
    public SaveOutcome saveDraft(Long boothId, Long userId, String requestBody) {
        editorGuard.requireEditor(boothId, userId);

        LayoutJson.SaveRequest request = readRequest(requestBody);
        if (request.expectedRevision() == null) {
            throw validationFailure("배치를 저장할 수 없습니다.",
                    "MISSING_EXPECTED_REVISION",
                    "expectedRevision이 필요합니다. 최초 저장은 0입니다.");
        }

        LayoutJson.LayoutDocument document = request.document();
        LayoutValidationResult validation = validator.validateForDraft(document);
        if (validation.hasErrors()) {
            throw new LayoutValidationFailedException("배치를 저장할 수 없습니다.", validation);
        }

        String layoutJson = LayoutJson.write(document);
        BoothLayoutDraft draft = drafts.findById(boothId).orElse(null);
        if (draft == null) {
            return new SaveOutcome(insertFirstDraft(boothId, userId, document, layoutJson,
                    request.expectedRevision()), validation.warnings());
        }
        return new SaveOutcome(updateExisting(boothId, userId, document, layoutJson,
                request.expectedRevision(), draft), validation.warnings());
    }

    /**
     * Copies the current draft into a new published version and points the booth at it.
     *
     * @throws BoothExpiredException            the lease is not valid — an expired booth cannot publish
     * @throws LayoutValidationFailedException  no draft, or the draft would not be valid published
     */
    @Transactional
    public PublishOutcome publish(Long boothId, Long userId) {
        editorGuard.requireEditor(boothId, userId);

        // Locked before validating, not after: the agents this publish is about to approve can be
        // deleted concurrently, and the layout's reference to them is JSON with no foreign key to
        // catch it. Agent deletion takes the same lock, so one of the two waits (spec 007 C-14,
        // invariant A-3).
        Booth booth = booths.findWithLockById(boothId)
                .orElseThrow(() -> new BoothNotFoundException(boothId));

        // Same predicate as every other occupancy question (research R-06).
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));

        BoothLayoutDraft draft = drafts.findById(boothId).orElseThrow(() -> validationFailure(
                "공개할 배치가 없습니다.", "NO_DRAFT", "먼저 배치를 저장해야 공개할 수 있습니다."));

        LayoutJson.LayoutDocument document = LayoutJson.parse(draft.getLayoutJson()).document();
        LayoutValidationResult validation = validator.validateForPublish(document, boothId);
        if (validation.hasErrors()) {
            throw new LayoutValidationFailedException("배치를 공개할 수 없습니다.", validation);
        }

        int nextVersion = published.highestVersionNo(boothId) + 1;
        // Flushed before the pointer moves: booths' foreign key checks the row exists, and within
        // one transaction the insert has to reach the database first.
        BoothLayoutPublishedVersion snapshot = published.saveAndFlush(new BoothLayoutPublishedVersion(
                boothId, nextVersion, draft.getSchemaVersion(), draft.getLayoutJson(), userId));
        booth.publishLayoutVersion(nextVersion);

        return new PublishOutcome(boothId, nextVersion, snapshot.getPublishedAt(), validation.warnings());
    }

    private BoothLayoutDraft insertFirstDraft(Long boothId, Long userId,
                                              LayoutJson.LayoutDocument document, String layoutJson,
                                              long expectedRevision) {
        if (expectedRevision != 0L) {
            // The client believes a draft exists; it does not. Report it as the same conflict rather
            // than quietly creating one, so a stale editor learns its view is wrong.
            throw new LayoutRevisionConflictException(0L);
        }
        // Deliberately not save(): with an assigned primary key that becomes a merge, and a merge
        // turns into an UPDATE when a concurrent first save has already committed — overwriting it
        // without a word. insertIfAbsent is one statement and reports the loser.
        if (drafts.insertIfAbsent(boothId, requireSchemaVersion(document), layoutJson, userId,
                Instant.now()) == 0) {
            throw new LayoutRevisionConflictException(currentRevisionOf(boothId));
        }
        return drafts.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
    }

    private BoothLayoutDraft updateExisting(Long boothId, Long userId,
                                            LayoutJson.LayoutDocument document, String layoutJson,
                                            long expectedRevision, BoothLayoutDraft draft) {
        int updated = drafts.updateIfRevisionMatches(boothId, expectedRevision,
                requireSchemaVersion(document), layoutJson, userId, Instant.now());
        if (updated == 0) {
            throw new LayoutRevisionConflictException(draft.getRevision());
        }
        return drafts.findById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));
    }

    private LayoutJson.SaveRequest readRequest(String requestBody) {
        try {
            return LayoutJson.parseSaveRequest(requestBody);
        } catch (LayoutParseException exception) {
            throw validationFailure("배치를 저장할 수 없습니다.", "MALFORMED_LAYOUT", exception.getMessage());
        }
    }

    /** Validation has already accepted the document, so the version is present by then. */
    private int requireSchemaVersion(LayoutJson.LayoutDocument document) {
        return document.schemaVersion() == null
                ? LayoutValidator.SUPPORTED_SCHEMA_VERSION
                : document.schemaVersion();
    }

    private long currentRevisionOf(Long boothId) {
        return drafts.findById(boothId).map(BoothLayoutDraft::getRevision).orElse(0L);
    }

    private LayoutValidationFailedException validationFailure(String message, String rule, String detail) {
        LayoutValidationResult result = new LayoutValidationResult();
        result.addError(rule, detail);
        return new LayoutValidationFailedException(message, result);
    }

    public record SaveOutcome(BoothLayoutDraft draft, List<ApiErrorDetail> warnings) { }

    public record PublishOutcome(Long boothId, int publishedVersion, Instant publishedAt,
                                 List<ApiErrorDetail> warnings) { }
}
