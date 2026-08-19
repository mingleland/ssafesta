package com.example.ssafesta.wallet;

/**
 * Spend request for {@link WalletService#spend} (spec 003 contracts/wallet-service-api.md).
 *
 * @param amount         positive; recorded in the ledger as a negative amount
 * @param idempotencyKey stable per business action, so a retried lease payment cannot charge twice
 */
public record CoinSpendCommand(Long userId, int amount, String reasonType, String referenceType,
                               String referenceId, String idempotencyKey) {
}
