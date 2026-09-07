package com.example.ssafesta.wallet;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * Raised when a member has no wallet. This is not a normal state: a wallet is created inside the
 * member-creation transaction (spec 003 FR-002), so reaching this means registration was
 * incomplete or the wallet was removed out of band. Callers surface it instead of silently
 * treating the balance as zero.
 *
 * <p>Carries {@code WALLET_NOT_FOUND} itself rather than leaving each controller to translate it.
 * While the translation lived in {@code WalletController} alone, the two other callers of
 * {@link WalletService#lockOwner} — booth lease and catalog purchase — reported the member's broken
 * state as a server fault (T-113, S15P21A604-402).
 *
 * <p>Construct it through {@link WalletService}'s own factory, never directly: a wallet that should
 * exist and does not has to leave a trace to investigate with.
 */
public class WalletNotFoundException extends ApiException {

    WalletNotFoundException() {
        // No id in the message — it is developer text, and the client already knows whose wallet it
        // asked about. requestId ties the response to the log line WalletService writes.
        super(ErrorCode.WALLET_NOT_FOUND);
    }
}
