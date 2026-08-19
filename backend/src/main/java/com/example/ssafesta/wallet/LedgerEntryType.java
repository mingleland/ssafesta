package com.example.ssafesta.wallet;

/**
 * Ledger entry types fixed by spec 003 ("충전/사용/보상/환불").
 *
 * <p>Each type carries the sign it is allowed to record, so an entry can never claim to be a
 * {@code REWARD} while draining the wallet.
 *
 * <p>Coins are earned in-game only — there is no cash top-up, now or later (team decision
 * 2026-08-19). {@code CHARGE} therefore means a system grant (signup/daily) or an administrator
 * increase, and that meaning is settled rather than provisional.
 */
public enum LedgerEntryType {

    CHARGE(true),
    SPEND(false),
    REWARD(true),
    REFUND(true);

    private final boolean credit;

    LedgerEntryType(boolean credit) {
        this.credit = credit;
    }

    public boolean isCredit() {
        return credit;
    }

    /** Converts a positive business amount into the signed amount stored in the ledger. */
    public int signedAmount(int positiveAmount) {
        if (positiveAmount <= 0) {
            throw new IllegalArgumentException("금액은 양수여야 합니다: " + positiveAmount);
        }
        return credit ? positiveAmount : -positiveAmount;
    }

    /** The type that records the given signed amount. */
    public static LedgerEntryType ofSignedAmount(int signedAmount) {
        if (signedAmount == 0) {
            throw new IllegalArgumentException("금액은 0일 수 없습니다.");
        }
        return signedAmount > 0 ? CHARGE : SPEND;
    }
}
