package com.example.ssafesta.game;

/**
 * What kind of asset the bytes are (contract §1).
 *
 * <p>{@code AUDIO} is absent on purpose. The FE port type has it and v1 does not support it
 * (#69 §3② 합의, 2026-08-25), so the absence is what produces {@code GAME_ASSET_KIND_UNSUPPORTED}
 * instead of a half-working audio path. Adding it later is an extra row in the §5 limit table plus
 * a codec check — the id issuance, state machine and retention rules are reused (계약 §10).
 */
public enum GameAssetKind {
    IMAGE,
    TILESET
}
