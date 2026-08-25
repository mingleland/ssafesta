package com.example.ssafesta.wallet;

/**
 * Grant request for {@link WalletService#credit} (spec 003 contracts/wallet-service-api.md).
 *
 * @param amount         positive; the ledger sign is applied by {@link LedgerEntryType}
 * @param idempotencyKey stable per business action — never include a timestamp or random value,
 *                       or a retry will be recorded as a second grant (spec 003 FR-007)
 */
public record CoinCreditCommand(Long userId, LedgerEntryType entryType, int amount, String reasonType,
                                String referenceType, String referenceId, String idempotencyKey) {

    public CoinCreditCommand {
        if (entryType != null && !entryType.isCredit()) {
            throw new IllegalArgumentException("지급에 사용할 수 없는 원장 유형입니다: " + entryType);
        }
    }
}
