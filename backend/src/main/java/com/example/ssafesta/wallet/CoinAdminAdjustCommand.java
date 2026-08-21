package com.example.ssafesta.wallet;

/**
 * Manual administrator correction (spec 003 FR-013). Recorded in the ledger like any other
 * movement, with the acting administrator kept as the reference.
 *
 * @param signedAmount positive to increase, negative to decrease; zero is rejected
 */
public record CoinAdminAdjustCommand(Long userId, int signedAmount, String note, Long actorUserId,
                                     String idempotencyKey) {
}
