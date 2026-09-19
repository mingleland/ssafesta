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
    private final GameAssetService assets;
    private final ArcadeSeatService seats;

    public GamePublishService(GameRepository games, GameDraftRepository drafts,
                              GamePublishedVersionRepository published, GameAccessGuard guard,
                              GameProjectValidator validator, GameAssetService assets,
                              ArcadeSeatService seats) {
        this.games = games;
        this.drafts = drafts;
        this.published = published;
        this.guard = guard;
        this.validator = validator;
        this.assets = assets;
        this.seats = seats;
    }

    /**
     * <p><b>자리 배정도 여기에 있다</b> (S15P21A604-942). {@code machineId} 가 오면 같은
     * 트랜잭션에서 오락실 캐비닛을 잡는다 — 자리가 거절되면 게시도 없던 일이 된다. 둘을 나누면
     * "게시는 됐는데 자리는 못 잡은" 상태가 생기고, 그것이 바로 게임 파트가 둘을 묶은 이유다
     * (프라임 자리에 검은 캐비닛, GitLab #256 · #238).
     *
     * @throws GameRevisionConflictException  the draft moved since the client read it
     * @throws GameValidationFailedException  no draft, or the stored project fails re-validation
     */
    @Transactional
    public PublishOutcome publish(Long gameId, Long userId, int expectedRevision, String machineId) {
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
        // Asset states are read inside this transaction, immediately before the version row is
        // appended, so a Draft that referenced a usable asset cannot be frozen after that asset
        // stopped being usable. Nothing can make one stop being usable yet — soft delete is not
        // built — so no row lock is taken here; the delete endpoint adds it (contract §9).
        validator.validateForPublish(project, storedJson, gameId, assets.stateSnapshot(gameId));

        int nextVersion = published.highestVersionNo(gameId) + 1;

        // Flushed before the pointer moves: the composite foreign key on
        // (games.id, games.published_version) checks the row exists, and within one transaction the
        // insert has to reach the database first. UNIQUE(game_id, version_no) is what actually makes
        // two concurrent publishes safe — this only picks the candidate number.
        GamePublishedVersion snapshot = published.saveAndFlush(new GamePublishedVersion(
                gameId, nextVersion, draft.getSchemaVersion(), storedJson, userId));
        game.publishVersion(nextVersion);
        games.save(game);

        // 게시가 먼저 성립한 뒤에 자리를 잡는다 — 자리가 거절되면 이 트랜잭션 전체가 되돌아가므로
        // 순서는 결과를 바꾸지 않는다. 읽기 쉬운 쪽을 택했다.
        if (machineId != null) {
            seats.claimOnPublish(game, userId, machineId);
        }

        // The draft is deliberately left in place: the creator keeps editing from where they were
        // (contracts §Publish). Deleting it would make publishing feel like handing the work away.
        return new PublishOutcome(gameId, nextVersion, snapshot.getPublishedAt(), machineId, List.of());
    }

    /**
     * {@code warnings} is always present and always empty — v1 defines no warning (contracts §rule).
     *
     * <p>{@code arcadeMachineId} 는 이번 호출이 잡은 자리다. 자리를 고르지 않고 게시했으면
     * {@code null} 이며, 그것이 이 게임에 자리가 없다는 뜻은 아니다 — 이전에 잡아 둔 자리는
     * 그대로 있다 (자리 이사는 내렸다 다시 올리는 것이다).
     */
    public record PublishOutcome(Long gameId, int publishedVersion, Instant publishedAt,
                                 String arcadeMachineId, List<String> warnings) { }
}
