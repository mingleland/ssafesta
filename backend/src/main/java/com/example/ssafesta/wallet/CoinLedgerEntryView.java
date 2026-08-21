package com.example.ssafesta.wallet;

import java.time.Instant;

/** Read model for transaction history (spec 003 FR-012, contracts/wallet-api.md). */
public record CoinLedgerEntryView(Long id, LedgerEntryType entryType, int amount, int balanceAfter,
                                  String reasonType, String referenceType, String referenceId,
                                  Instant createdAt) {

    static CoinLedgerEntryView of(CoinLedgerEntry entry) {
        return new CoinLedgerEntryView(entry.getId(), entry.getEntryType(), entry.getAmount(),
                entry.getBalanceAfter(), entry.getReasonType(), entry.getReferenceType(),
                entry.getReferenceId(), entry.getCreatedAt());
    }
}
