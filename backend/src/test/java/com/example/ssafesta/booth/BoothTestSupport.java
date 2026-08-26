package com.example.ssafesta.booth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared helpers for booth lease tests. */
final class BoothTestSupport {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private BoothTestSupport() {
    }

    /** Creates a member with a wallet and the signup grant, as registration would. */
    static Long createMemberWithWallet(UserRepository users, WalletService wallets, String prefix) {
        Long userId = users.save(new User(prefix + SEQUENCE.incrementAndGet() + "_" + System.nanoTime())).getId();
        wallets.openWallet(userId);
        return userId;
    }

    /**
     * Frees every slot. There are only twelve of them and leases run for 24 hours, so tests sharing
     * one database would otherwise run out after the first few. Call this before each test rather
     * than after, so a class is unaffected by whatever another class left behind.
     */
    static void releaseAllSlots(JdbcTemplate jdbc) {
        jdbc.update("UPDATE booth_leases SET status = 'EXPIRED' WHERE status = 'ACTIVE'");
        jdbc.update("UPDATE booths SET current_slot_id = NULL, status = 'INACTIVE' WHERE current_slot_id IS NOT NULL");
    }

    /**
     * Asserts spec 003's invariant I-1 still holds after a lease touched the wallet. A lease test
     * that leaves the balance disagreeing with the ledger has not passed, whatever else it checked.
     */
    static void assertBalanceMatchesLedger(WalletService wallets, Long userId) {
        var wallet = wallets.requireWallet(userId);
        assertEquals(wallet.getBalance(), wallets.ledgerSumOf(wallet.getId()),
                "잔액과 원장 합계가 일치해야 합니다 — userId=" + userId);
    }
}
