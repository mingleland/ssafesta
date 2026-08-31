package com.example.ssafesta.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared helpers for wallet tests. */
final class WalletTestSupport {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private WalletTestSupport() {
    }

    /** Creates a member row so a wallet can reference it, with a nickname unique per test run. */
    static Long createMember(UserRepository users, String prefix) {
        // nickname VARCHAR(30) 예산. 태그는 헬퍼 구분용이다 — T-103, BoothTestSupport 참고.
        return users.save(new User(prefix + "w" + SEQUENCE.incrementAndGet())).getId();
    }

    /**
     * Asserts invariant I-1: the stored balance equals the ledger total (spec 003 FR-006, SC-001).
     * Every wallet test ends with this — a scenario that passes its own assertions while leaving
     * the balance and the ledger disagreeing has not actually passed.
     */
    static void assertBalanceMatchesLedger(WalletService wallets, Long userId) {
        Wallet wallet = wallets.requireWallet(userId);
        assertEquals(wallet.getBalance(), wallets.ledgerSumOf(wallet.getId()),
                "잔액과 원장 합계가 일치해야 합니다 — userId=" + userId);
    }
}
