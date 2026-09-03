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

    /** Creates a member with a wallet and the signup grant, as registration would. */
    public static Long createMemberWithWallet(UserRepository users, WalletService wallets, String prefix) {
        // nickname 은 VARCHAR(30) 이다 (V1:6). nanoTime 을 붙이면 그것만으로 17자를 먹어 prefix 가
        // 조금만 길어도 넘친다 (T-103). SEQUENCE 는 JVM 당 유일하고 컨테이너는 실행마다 새로 뜨므로
        // 실행 간 충돌이 없다. 헬퍼마다 SEQUENCE 가 따로라 태그 한 글자로 서로를 가른다.
        Long userId = users.save(new User(prefix + "b" + SEQUENCE.incrementAndGet())).getId();
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
