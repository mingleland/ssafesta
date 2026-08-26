package com.example.ssafesta.game;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes the current draft into a new immutable version and moves the public pointer.
 *
 * <p>Both steps are <b>one transaction</b>. Splitting them would allow a version that exists but
 * nobody can see, or a pointer to a version that was never written — the same reasoning that put
 * coin spending and lease creation together in spec 004 and layout append and pointer move together
 * in spec 005.
 */
@Service
public class GamePublishService {

    private final GameRepository games;
    private final GameDraftRepository drafts;
    private final GamePublishedVersionRepository published;
    private final GameAccessGuard guard;
    private final GameProjectValidator validator;

    public GamePublishService(GameRepository games, GameDraftRepository drafts,
                              GamePublishedVersionRepository published, GameAccessGuard guard,
                              GameProjectValidator validator) {
        this.games = games;
        this.drafts = drafts;
        this.published = published;
        this.guard = guard;
        this.validator = validator;
    }

    /**
     * @throws GameRevisionConflictException  the draft moved since the client read it
     * @throws GameValidationFailedException  no draft, or the stored project fails re-validation
     */
    @Transactional
    public PublishOutcome publish(Long gameId, Long userId, int expectedRevision) {
        Game game = guard.requireOwnedLive(gameId, userId);

        GameDraft draft = drafts.findById(gameId).orElseThrow(() -> GameValidationFailedException.of(
                "공개할 게임이 없습니다.", "MALFORMED_PROJECT", "먼저 게임을 저장해야 공개할 수 있습니다."));

        // Checked here rather than in SQL: publishing does not write the draft, so there is no
        // conditional UPDATE to piggyback on. Inside one transaction the read is stable.
        if (draft.getRevision() != expectedRevision) {
            throw new GameRevisionConflictException(draft.getRevision());
        }

        // Re-validated even though the draft passed on the way in. The rule set is not identical —
        // Publish adds the policies other people's play depends on — and a project stored by an
        // older server version has never been checked against today's rules.
        String storedJson = draft.getProjectJson();
        JsonNode project = GameProjectJson.parse(storedJson);
        validator.validateForPublish(project, storedJson, gameId);

        int nextVersion = published.highestVersionNo(gameId) + 1;

        // Flushed before the pointer moves: the composite foreign key on
        // (games.id, games.published_version) checks the row exists, and within one transaction the
        // insert has to reach the database first. UNIQUE(game_id, version_no) is what actually makes
        // two concurrent publishes safe — this only picks the candidate number.
        GamePublishedVersion snapshot = published.saveAndFlush(new GamePublishedVersion(
                gameId, nextVersion, draft.getSchemaVersion(), storedJson, userId));
        game.publishVersion(nextVersion);
        games.save(game);

        // The draft is deliberately left in place: the creator keeps editing from where they were
        // (contracts §Publish). Deleting it would make publishing feel like handing the work away.
        return new PublishOutcome(gameId, nextVersion, snapshot.getPublishedAt(), List.of());
    }

    /** {@code warnings} is always present and always empty — v1 defines no warning (contracts §rule). */
    public record PublishOutcome(Long gameId, int publishedVersion, Instant publishedAt,
                                 List<String> warnings) { }
}
