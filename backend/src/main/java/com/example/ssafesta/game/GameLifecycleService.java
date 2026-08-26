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
    private final GameAccessGuard guard;
    private final GameProperties properties;

    public GameLifecycleService(GameRepository games, GameDraftRepository drafts,
                                GamePublishedVersionRepository published, GameAccessGuard guard,
                                GameProperties properties) {
        this.games = games;
        this.drafts = drafts;
        this.published = published;
        this.guard = guard;
        this.properties = properties;
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
     * blocking the delete. Member withdrawal reuses this path.
     */
    private void hardDelete(Game game) {
        drafts.findById(game.getId()).ifPresent(drafts::delete);
        published.deleteAll(published.findAllByGameIdOrderByVersionNoDesc(game.getId()));
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
