package com.example.ssafesta.game;

/**
 * Whether other people may start this game (spec 019 FR-030).
 *
 * <p>Deliberately <b>independent</b> of whether a published version exists. A {@code PUBLIC} game
 * with no published version is legal — the creator turned the switch on before publishing — and the
 * Runtime reports that as {@code GAME_NOT_PUBLISHED}. Collapsing the two into one flag would force
 * the editor to publish before it could set visibility, which is an order nobody asked for
 * (contracts §공개 설정 변경).
 */
public enum GameVisibility {
    PRIVATE,
    PUBLIC
}
