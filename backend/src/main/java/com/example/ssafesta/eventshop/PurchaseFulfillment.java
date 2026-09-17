package com.example.ssafesta.eventshop;

/**
 * A prize purchase's processing state (S15P21A604-836, GitLab #217 4번).
 *
 * <p>Separate from the coin ledger on purpose — a {@code SPEND} entry says the coin left the
 * buyer's wallet, not that the prize reached them. This is the second, independent fact: has the
 * prize actually been handed over.
 *
 * <p>{@link #FULFILLED} and {@link #CANCELLED} are terminal. Nothing reopens a purchase once the
 * prize is out the door or the order is void — a mistake there is corrected with a fresh
 * administrator adjustment (coin) and a note, not by rewinding this state.
 */
public enum PurchaseFulfillment {
    /** Just charged. The default a purchase starts in. */
    PURCHASED,
    /** An administrator has picked it up for handling (e.g. queued for shipping). */
    PENDING,
    /** The prize reached the buyer. Terminal. */
    FULFILLED,
    /** Voided — never fulfilled. Terminal. */
    CANCELLED;

    /** {@code PURCHASED} may go anywhere; {@code PENDING} may still finish or void; the rest is terminal. */
    public boolean canTransitionTo(PurchaseFulfillment next) {
        return switch (this) {
            case PURCHASED -> next == PENDING || next == FULFILLED || next == CANCELLED;
            case PENDING -> next == FULFILLED || next == CANCELLED;
            case FULFILLED, CANCELLED -> false;
        };
    }
}
