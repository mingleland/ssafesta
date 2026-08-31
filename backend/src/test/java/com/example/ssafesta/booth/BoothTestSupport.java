package com.example.ssafesta.booth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared helpers for booth lease tests. */
public final class BoothTestSupport {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private BoothTestSupport() {
    }

    /** {@code users.nickname} 은 VARCHAR(30) 이다. 넘기면 INSERT 가 죽는다. */
    private static final int MAX_NICKNAME = 30;

    /**
     * Creates a member with a wallet and the signup grant, as registration would.
     *
     * <p>The name has to be unique and it has to fit the column. Uniqueness lives in the tail, so
     * the <b>prefix</b> is what gets trimmed — cutting the tail would let two tests collide. This
     * used to be {@code prefix + sequence + nanoTime} with no bound, which fit only because the
     * sequence stayed short; adding a test class pushed it one digit further and the insert failed
     * in an unrelated suite.
     */
    public static Long createMemberWithWallet(UserRepository users, WalletService wallets, String prefix) {
        // 9 digits of nanoTime is sub-second entropy — enough between runs against one container.
        String tail = SEQUENCE.incrementAndGet() + "_" + System.nanoTime() % 1_000_000_000L;
        String head = prefix.length() > MAX_NICKNAME - tail.length()
                ? prefix.substring(0, Math.max(0, MAX_NICKNAME - tail.length()))
                : prefix;
        Long userId = users.save(new User(head + tail)).getId();
        wallets.openWallet(userId);
        return userId;
    }

    /**
     * Frees every slot. There are only twelve of them and leases run for 24 hours, so tests sharing
     * one database would otherwise run out after the first few. Call this before each test rather
     * than after, so a class is unaffected by whatever another class left behind.
     */
    public static void releaseAllSlots(JdbcTemplate jdbc) {
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
