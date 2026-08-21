package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Concurrency guarantees (spec 003 User Story 2, SC-002/SC-003/SC-004).
 *
 * <p>Repeated deliberately: a race that passes once has not been proven to be closed. Threads are
 * released together by a latch so the requests genuinely overlap rather than run in sequence.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WalletConcurrencyIntegrationTest {

    private static final int REPEATS = 5;

    @Autowired private WalletService wallets;
    @Autowired private DailyCoinGrantService dailyGrants;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private WalletProperties properties;

    @RepeatedTest(REPEATS)
    void twoSimultaneousSpendsCannotBothSucceedWhenOnlyOneIsAffordable() throws Exception {
        Long userId = createMember(users, "동시차감");
        wallets.openWallet(userId);
        int affordableOnce = properties.initialGrant() - 10; // 200 → 190: only one of two fits.

        List<Outcome> outcomes = runTogether(2, index -> () -> {
            try {
                wallets.spend(new CoinSpendCommand(userId, affordableOnce, "LEASE_PAYMENT",
                        "BOOTH_LEASE", String.valueOf(index), "LEASE_PAYMENT:CONCURRENT:" + userId + ":" + index));
                return Outcome.SUCCESS;
            } catch (InsufficientCoinException exception) {
                return Outcome.REFUSED;
            }
        });

        assertEquals(1, count(outcomes, Outcome.SUCCESS), "차감은 정확히 한 건만 성공해야 합니다.");
        assertEquals(1, count(outcomes, Outcome.REFUSED), "다른 한 건은 잔액 부족으로 거부돼야 합니다.");
        assertEquals(properties.initialGrant() - affordableOnce, wallets.balanceOf(userId));
        assertTrue(wallets.balanceOf(userId) >= 0, "잔액은 음수가 될 수 없습니다.");
        assertBalanceMatchesLedger(wallets, userId);
    }

    @RepeatedTest(REPEATS)
    void simultaneousRetriesOfTheSameSpendChangeTheBalanceOnce() throws Exception {
        Long userId = createMember(users, "동시멱등");
        wallets.openWallet(userId);
        String key = "LEASE_PAYMENT:RETRY:" + userId;

        List<Outcome> outcomes = runTogether(4, index -> () -> {
            wallets.spend(new CoinSpendCommand(userId, 25, "LEASE_PAYMENT", "BOOTH_LEASE", "1", key));
            return Outcome.SUCCESS;
        });

        assertEquals(4, count(outcomes, Outcome.SUCCESS), "재시도는 모두 예외 없이 같은 결과를 받아야 합니다.");
        assertEquals(properties.initialGrant() - 25, wallets.balanceOf(userId), "잔액은 한 번만 줄어야 합니다.");
        assertEquals(1, ledger.findAll().stream()
                .filter(entry -> key.equals(entry.getIdempotencyKey())).count());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @RepeatedTest(REPEATS)
    void simultaneousFirstAccessesGrantTheDailyCoinsOnce() throws Exception {
        Long userId = createMember(users, "동시일일");
        wallets.openWallet(userId);
        LocalDate today = LocalDate.of(2026, 8, 19);
        AtomicInteger granted = new AtomicInteger();

        runTogether(6, index -> () -> {
            if (dailyGrants.grantIfDue(userId, today)) {
                granted.incrementAndGet();
            }
            return Outcome.SUCCESS;
        });

        assertEquals(1, granted.get(), "일일 지급을 기록한 요청은 하나여야 합니다.");
        assertEquals(properties.initialGrant() + properties.dailyGrant(), wallets.balanceOf(userId));
        assertEquals(1, ledger.findAll().stream()
                .filter(entry -> DailyCoinGrantService.dailyGrantKey(userId, today)
                        .equals(entry.getIdempotencyKey()))
                .count());
        assertBalanceMatchesLedger(wallets, userId);
    }

    private List<Outcome> runTogether(int threads, TaskFactory factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                Callable<Outcome> task = factory.create(index);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(List<Outcome> outcomes, Outcome expected) {
        return outcomes.stream().filter(outcome -> outcome == expected).count();
    }

    private enum Outcome { SUCCESS, REFUSED }

    private interface TaskFactory {
        Callable<Outcome> create(int index);
    }
}
