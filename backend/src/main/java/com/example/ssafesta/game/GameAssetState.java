package com.example.ssafesta.game;

/**
 * What the validator is allowed to know about one referenced asset.
 *
 * <p>A plain set of usable ids would collapse four different contract outcomes into "not in the
 * set" — {@code GAME_ASSET_NOT_FOUND}, {@code NOT_READY}, {@code DELETED} and {@code FORBIDDEN} are
 * distinct in §6 and the editor shows different text for each. So the caller hands over a snapshot
 * of states and {@link GameProjectValidator} stays a function of its input.
 */
public enum GameAssetState {
    READY,
    UPLOADING,
    FAILED,
    DELETED,
    /** No row for this {@code assetId} in this game. */
    MISSING
}
