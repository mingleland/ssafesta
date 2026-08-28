package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Answers "may this user touch this game" in one place.
 *
 * <p>The three refusals are separate codes because the client's next move differs: a missing game is
 * a dead link, a deleted one may be restorable, and someone else's is a permission problem. Folding
 * them into one 404 would also leak less than you would hope — the editor already knows which game
 * id it asked for.
 */
@Component
class GameAccessGuard {

    private final GameRepository games;

    GameAccessGuard(GameRepository games) {
        this.games = games;
    }

    /** For Authoring: the caller must own a live game. */
    Game requireOwnedLive(Long gameId, Long userId) {
        Game game = requireExisting(gameId);
        if (game.isDeleted()) {
            throw new ApiException(ErrorCode.GAME_DELETED);
        }
        requireOwner(game, userId);
        return game;
    }

    /** For restore: the caller must own it, and it must still be soft-deleted. */
    Game requireOwnedAny(Long gameId, Long userId) {
        Game game = requireExisting(gameId);
        requireOwner(game, userId);
        return game;
    }

    Game requireExisting(Long gameId) {
        return games.findById(gameId).orElseThrow(() -> new ApiException(ErrorCode.GAME_NOT_FOUND));
    }

    private void requireOwner(Game game, Long userId) {
        if (!game.isOwnedBy(userId)) {
            // GAME_FORBIDDEN, not GAME_NOT_FOUND: hiding existence here would be a guess at a threat
            // model the contract does not have, and the contract names this code for exactly this
            // case (§봉투 code 표).
            throw new ApiException(ErrorCode.GAME_FORBIDDEN);
        }
    }
}
