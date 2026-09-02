package com.example.ssafesta.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the editable copy of a game (contracts §Draft 저장).
 *
 * <p>{@code revision} is issued by the server and <b>stamped into the stored project</b>. That reads
 * like a violation of FR-034's no-correction rule but is not: what may never be rewritten is data the
 * user authored — coordinates, unknown fields, references — and the revision counter was never
 * theirs. The contract carves this out explicitly, because the editor sends the old value in
 * {@code expectedRevision} and then strict-checks that the response's {@code project.revision}
 * equals the response's {@code revision}. Storing the client's stale number would break that check
 * on every save.
 */
@Service
public class GameDraftService {

    private final GameDraftRepository drafts;
    private final GameAccessGuard guard;
    private final GameProjectValidator validator;
    private final GameAssetService assets;

    public GameDraftService(GameDraftRepository drafts, GameAccessGuard guard,
                            GameProjectValidator validator, GameAssetService assets) {
        this.drafts = drafts;
        this.guard = guard;
        this.validator = validator;
        this.assets = assets;
    }

    /**
     * The current draft, or empty when the game exists but has never been saved.
     *
     * <p>Empty becomes {@code 204 No Content} at the controller — not {@code 404}. "The game exists
     * and has no draft yet" is a normal first visit, and the editor reads 204 as "keep the starter
     * project". A 404 would make the editor open in an error state on every new game, with no
     * exception and no failing test to show it (#104 ①).
     */
    @Transactional(readOnly = true)
    public Optional<DraftView> findDraft(Long gameId, Long userId) {
        guard.requireOwnedLive(gameId, userId);
        return drafts.findById(gameId).map(DraftView::of);
    }

    /**
     * Stores the working copy. Never publishes anything.
     *
     * @throws GameRevisionConflictException  someone saved first, or the client's expectation was
     *                                        never valid for the current state
     * @throws GameValidationFailedException  the project breaks a rule
     */
    @Transactional
    public DraftView save(Long gameId, Long userId, int expectedRevision, String body) {
        guard.requireOwnedLive(gameId, userId);

        JsonNode parsed = GameProjectJson.parse(body);
        // The validator stays a function of its input, so the asset states it needs are read here
        // and handed over. Read inside this transaction, so the states it judges are the states the
        // save is committed against.
        validator.validateForDraft(parsed, body, gameId, assets.stateSnapshot(gameId));

        // Deterministic, so it can be stamped before the write: expectedRevision has to equal the
        // stored value for the update to be accepted at all, and the first save is 0 → 1.
        int nextRevision = expectedRevision + 1;
        String storedJson = withRevision(parsed, nextRevision);
        String schemaVersion = GameProjectJson.textAt(parsed, "schemaVersion");
        Instant now = Instant.now();

        if (drafts.findById(gameId).isEmpty()) {
            return insertFirst(gameId, userId, expectedRevision, schemaVersion, storedJson, now);
        }
        if (drafts.updateIfRevisionMatches(gameId, expectedRevision, schemaVersion, storedJson, userId, now) == 0) {
            throw new GameRevisionConflictException(currentRevisionOf(gameId));
        }
        return DraftView.of(drafts.findById(gameId).orElseThrow());
    }

    /**
     * The very first save of a game that has no draft row.
     *
     * <p>{@code expectedRevision: 0} <b>is</b> that request — the starter project the editor holds
     * carries revision 0, so this is the only value that can arrive here honestly. Any other value
     * means the client believes a draft exists; reporting a conflict tells a stale editor its view is
     * wrong instead of quietly creating one under it (contracts §Draft 저장).
     */
    private DraftView insertFirst(Long gameId, Long userId, int expectedRevision,
                                  String schemaVersion, String storedJson, Instant now) {
        if (expectedRevision != 0) {
            throw new GameRevisionConflictException(0);
        }
        if (drafts.insertIfAbsent(gameId, schemaVersion, storedJson, userId, now) == 0) {
            // Another first save committed between the findById above and this insert. Reporting the
            // conflict is the point: save() would have merged and overwritten it in silence.
            throw new GameRevisionConflictException(currentRevisionOf(gameId));
        }
        return DraftView.of(drafts.findById(gameId).orElseThrow());
    }

    /** Writes the server's revision into the envelope, leaving every other field byte-for-byte. */
    private String withRevision(JsonNode project, int revision) {
        ObjectNode copy = ((ObjectNode) project).deepCopy();
        copy.put("revision", revision);
        return GameProjectJson.write(copy);
    }

    private int currentRevisionOf(Long gameId) {
        return drafts.findById(gameId).map(GameDraft::getRevision).orElse(0);
    }

    /** What both {@code GET} and {@code PUT} return — the contract fixes them to one shape. */
    public record DraftView(Long gameId, int revision, String projectJson, Instant updatedAt) {

        static DraftView of(GameDraft draft) {
            return new DraftView(draft.getGameId(), draft.getRevision(),
                    draft.getProjectJson(), draft.getUpdatedAt());
        }
    }
}
