package com.example.ssafesta.wallet;

/**
 * Raised when a member has no wallet. This is not a normal state: a wallet is created inside the
 * member-creation transaction (spec 003 FR-002), so reaching this means registration was
 * incomplete or the wallet was removed out of band. Callers surface it instead of silently
 * treating the balance as zero.
 */
public class WalletNotFoundException extends RuntimeException {

    private final Long userId;

    public WalletNotFoundException(Long userId) {
        super("지갑을 찾을 수 없습니다 — userId=" + userId);
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
}
