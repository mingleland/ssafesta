package com.example.ssafesta.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two caps a creator's library lives under (contracts §게임 생성 · §삭제본 보관).
 *
 * <p>They are <b>independent</b>: filling the recycle bin never blocks making a new game, and a full
 * library never blocks deleting one. Sharing one budget would mean a creator at the limit could not
 * delete anything to get under it.
 *
 * <p>Configurable rather than constants because the contract calls them server settings, and the
 * documented v1 defaults are what the tests pin. A client that hard-codes 20 goes stale the moment
 * this changes, which is why {@code GAME_LIMIT_EXCEEDED} carries the number in its message.
 *
 * @param liveLimit    live (not soft-deleted) games per account
 * @param deletedLimit soft-deleted games kept per account; a further deletion evicts the oldest
 */
@ConfigurationProperties("app.game")
public record GameProperties(int liveLimit, int deletedLimit) {

    public GameProperties {
        if (liveLimit < 1) {
            throw new IllegalStateException("app.game.live-limit 은 1 이상이어야 합니다: " + liveLimit);
        }
        if (deletedLimit < 0) {
            throw new IllegalStateException("app.game.deleted-limit 은 0 이상이어야 합니다: " + deletedLimit);
        }
    }
}
