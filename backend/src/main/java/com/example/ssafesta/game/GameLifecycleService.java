package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, listing, hiding, deleting and restoring games (contracts §게임 생성 · §내 게임 목록 ·
 * §공개 설정 변경 · §삭제 · §복구).
 *
 * <p>Two caps run through here, and both are enforced at the moment a slot is <b>taken</b>: creating
 * and restoring go through the same live-game gate, because restoring occupies a live slot just as
 * much as creating does.
 */
@Service
public class GameLifecycleService {

    private final GameRepository games;
    private final GameDraftRepository drafts;
    private final GamePublishedVersionRepository published;
    private final GameAssetRepository assets;
    private final ArcadeMachineBindingRepository bindings;
    private final GameAccessGuard guard;
    private final GameProperties properties;
    private final ArcadeSeatService seats;

    public GameLifecycleService(ArcadeSeatService seats, GameRepository games, GameDraftRepository drafts,
                                GamePublishedVersionRepository published, GameAssetRepository assets,
                                ArcadeMachineBindingRepository bindings, GameAccessGuard guard,
                                GameProperties properties) {
        this.games = games;
        this.drafts = drafts;
        this.published = published;
        this.assets = assets;
        this.bindings = bindings;
        this.guard = guard;
        this.properties = properties;
        this.seats = seats;
    }

    /**
     * Creates an empty game. <b>No draft is created</b> — the editor holds a starter project and
     * first-saves it with {@code expectedRevision: 0}.
     *
     * <p>Making one here would put data the user never authored into the database, and would leak the
     * starter-template choice (FR-044, six of them) into the server.
     */
    @Transactional
    public GameSummary create(Long userId, String title) {
        requireTitle(title);
        requireLiveSlot(userId, "게임은 최대 %d개까지 만들 수 있습니다. 기존 게임을 삭제한 뒤 다시 시도해 주세요.");
        return GameSummary.of(games.save(new Game(userId, title)));
    }

    /** The owner's library, live first, each group newest-first. Soft-deleted rows are included. */
    @Transactional(readOnly = true)
    public List<GameSummary> listOwned(Long userId) {
        return games.findAllOwnedOrderByLiveThenUpdated(userId).stream().map(GameSummary::of).toList();
    }

    /**
     * Flips {@code visibility}.
     *
     * <p>Allowed even when nothing is published yet. Visibility and publishing are independent axes —
     * {@code games.published_version} is nullable for that reason — and a {@code PUBLIC} game with no
     * version shows up as {@code GAME_NOT_PUBLISHED} at the Runtime endpoint. Refusing it here would
     * force a publish-then-publicise order nobody asked for, and "I made it public but it is not
     * visible" is a sentence the editor can say (contracts §공개 설정 변경).
     */
    @Transactional
    public GameSummary changeVisibility(Long gameId, Long userId, GameVisibility next) {
        Game game = guard.requireOwnedLive(gameId, userId);
        game.changeVisibility(next);
        if (next != GameVisibility.PUBLIC) {
            // 내리면 자리가 풀린다 (S15P21A604-942). v1 에 게시 취소 엔드포인트가 없어서, 걸린
            // 게임이 공개를 멈추는 길은 여기와 소프트 삭제 둘뿐이다. 자리를 쥔 채로 두면 오락실
            // 프라임 칸에 아무도 못 켜는 캐비닛이 남는다 — 묶어 둔 이유가 그것이다.
            seats.release(gameId);
        }
        return GameSummary.of(games.save(game));
    }

    /**
     * Ordinary deletion: soft.
     *
     * <p>When the recycle bin is already full the <b>oldest</b> deleted game is removed for good and
     * the deletion still succeeds. Refusing would be worse — the user asked to get rid of something
     * and would be told to tidy up first. The eviction happens inside this transaction, which is what
     * keeps the cap a bound rather than a target and removes the need for any sweeper.
     *
     * @return the game evicted to make room, if any — the caller may want to say so
     */
    @Transactional
    public Optional<Long> softDelete(Long gameId, Long userId) {
        Game game = guard.requireOwnedAny(gameId, userId);
        if (game.isDeleted()) {
            // Idempotent on the surface only. The contract answers a repeat with 404 GAME_DELETED and
            // tells clients to read it as "already done" rather than as a failure — a response that
            // was lost mid-flight retries into exactly this branch.
            throw new ApiException(ErrorCode.GAME_DELETED);
        }

        Optional<Long> evicted = Optional.empty();
        if (games.countDeletedByOwner(userId) >= properties.deletedLimit()) {
            evicted = games.findOldestDeletedByOwner(userId).map(oldest -> {
                hardDelete(oldest);
                return oldest.getId();
            });
        }

        // 휴지통에 있는 게임의 바인딩은 목록에서 빠지므로 (S15P21A604-940) 자리를 쥔 채 두면
        // 화면에는 비었는데 잡으려 하면 이미 점유인 자리가 된다.
        seats.release(gameId);

        game.softDelete(Instant.now());
        games.save(game);
        return evicted;
    }

    /**
     * Brings a soft-deleted game back, keeping the visibility it had.
     *
     * <p>Deletion was never a visibility change, so restoring is not one either.
     *
     * @throws ApiException {@code GAME_LIMIT_EXCEEDED} when the live cap is full
     */
    @Transactional
    public GameSummary restore(Long gameId, Long userId) {
        Game game = guard.requireOwnedAny(gameId, userId);
        if (!game.isDeleted()) {
            // Idempotent, and deliberately asymmetric with delete's 404. The target state is already
            // reached, so there is nothing to report; making it an error would need a new code
            // (GAME_NOT_DELETED) for no gain. Delete's 404 is different — "already deleted" is state
            // the client needs to know (contracts §복구).
            return GameSummary.of(game);
        }
        requireLiveSlot(userId, "게임은 최대 %d개까지 활성화할 수 있습니다. 다른 게임을 삭제한 뒤 복구해 주세요.");
        game.restore();
        return GameSummary.of(games.save(game));
    }

    /**
     * Removes a game and everything hanging off it.
     *
     * <p>Order matters: the published rows go before the game, and the composite foreign key's
     * {@code ON DELETE SET NULL (published_version)} clears the pointer on the way rather than
     * blocking the delete. Member withdrawal deletes the same graph with its own SQL.
     *
     * <p>The assets go first and in two steps, because their bytes are outside the database: the
     * coordinates move to the delete queue in this transaction, then the rows go. Skipping them made
     * {@code games.delete} fail on the {@code game_id} foreign key — a creator whose oldest deleted
     * game had ever held an image could not delete another game at all (S15P21A604-485).
     */
    private void hardDelete(Game game) {
        assets.enqueueAllObjects(game.getId());
        assets.deleteAllByGameId(game.getId());
        drafts.findById(game.getId()).ifPresent(drafts::delete);
        published.deleteAll(published.findAllByGameIdOrderByVersionNoDesc(game.getId()));
        // Same failure as the assets above, one table later: V27 gave arcade machines a plain
        // foreign key to games with no ON DELETE, on purpose, so the deleting side has to name what
        // it removes. Withdrawal already did; eviction did not, and a creator whose oldest deleted
        // game sat on a machine could not delete anything else (S15P21A604-681).
        bindings.deleteByGameId(game.getId());
        games.flush();
        games.delete(game);
    }

    /**
     * The live-game gate shared by creation and restore.
     *
     * <p>The limit goes in the top-level {@code message}, not in an {@code errors[]} rule. That is the
     * slot the envelope reserves for the sentence a user reads (#58 T058), and the number is a server
     * setting — a client that hard-coded it would print the wrong figure the moment it changed.
     */
    private void requireLiveSlot(Long userId, String messageTemplate) {
        if (games.countLiveByOwner(userId) >= properties.liveLimit()) {
            throw new ApiException(ErrorCode.GAME_LIMIT_EXCEEDED,
                    messageTemplate.formatted(properties.liveLimit()));
        }
    }

    private void requireTitle(String title) {
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "게임 제목이 올바르지 않습니다.",
                    List.of(ApiErrorDetail.field("title", "1~100자여야 합니다.")), null);
        }
    }

    /** The list item and the mutation responses share one shape (contracts §내 게임 목록). */
    public record GameSummary(Long gameId, String title, GameVisibility visibility,
                              Integer publishedVersion, Instant updatedAt, Instant deletedAt) {

        static GameSummary of(Game game) {
            return new GameSummary(game.getId(), game.getTitle(), game.getVisibility(),
                    game.getPublishedVersion(), game.getUpdatedAt(), game.getDeletedAt());
        }
    }
}
