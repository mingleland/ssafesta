package com.example.ssafesta.minigame;

/**
 * Lifecycle of one minigame play (spec 014).
 *
 * <p>Both terminal states are terminal on purpose. {@link #REJECTED} in particular must not be
 * retryable: if a rejected session stayed playable, a client could keep resubmitting until one
 * claim happened to land inside the elapsed-time tolerance, which is the whole of what that check
 * buys us.
 */
public enum MinigameSessionStatus {

    /** Issued, not yet reported. Stays here forever if the player walks away (C-05 무보상). */
    IN_PROGRESS,

    /** Reported and judged — including a timeout, which is a valid play with no reward. */
    COMPLETED,

    /** The reported stop time did not agree with the server's elapsed time (FR-008). */
    REJECTED;

    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }
}
