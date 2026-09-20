package com.example.ssafesta.wallet;

/**
 * Outcome of a wallet movement.
 *
 * @param alreadyApplied {@code true} when the idempotency key had already been recorded, in which
 *                       case {@code balanceAfter} is the balance from the original entry and no
 *                       new movement happened (spec 003 FR-007)
 */
public record LedgerResult(Long entryId, int balanceAfter, boolean alreadyApplied) {

    static LedgerResult applied(CoinLedgerEntry entry) {
        return new LedgerResult(entry.getId(), entry.getBalanceAfter(), false);
    }

    static LedgerResult alreadyApplied(CoinLedgerEntry entry) {
        return new LedgerResult(entry.getId(), entry.getBalanceAfter(), true);
    }
}
