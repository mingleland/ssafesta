package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency guarantees (spec 004 User Story 2, SC-001/SC-002).
 *
 * <p>Repeated on purpose: a race that passes once has not been shown to be closed. Threads are
 * released together by a latch so the requests really overlap.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLeaseConcurrencyIntegrationTest {

    private static final int REPEATS = 5;

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private LeaseProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @RepeatedTest(REPEATS)
    void twoMembersRacingForOneSlotProduceExactlyOneLease() throws Exception {
        Long first = createMemberWithWallet(users, wallets, "경합A");
        Long second = createMemberWithWallet(users, wallets, "경합B");
        Long slotId = freeSlotId();
        int firstBefore = wallets.balanceOf(first);
        int secondBefore = wallets.balanceOf(second);

        List<Result> results = runTogether(List.of(
                () -> attempt(first, slotId),
                () -> attempt(second, slotId)));

        assertEquals(1, results.stream().filter(Result::succeeded).count(),
                "한 슬롯은 한 명에게만 임대돼야 합니다.");
        assertEquals(1, results.stream().filter(result -> !result.succeeded()).count());

        // The loser must be left exactly as they started — this is the "coins gone, no booth" case.
        int firstAfter = wallets.balanceOf(first);
        int secondAfter = wallets.balanceOf(second);
        int totalSpent = (firstBefore - firstAfter) + (secondBefore - secondAfter);
        assertEquals(properties.priceCoin(), totalSpent, "코인은 성공한 한 건에서만 차감돼야 합니다.");
        assertBalanceMatchesLedger(wallets, first);
        assertBalanceMatchesLedger(wallets, second);
    }

    @RepeatedTest(REPEATS)
    void aLoserOfTheRaceKeepsEveryCoin() throws Exception {
        Long winner = createMemberWithWallet(users, wallets, "승자");
        Long loser = createMemberWithWallet(users, wallets, "패자");
        Long slotId = freeSlotId();
        int loserBefore = wallets.balanceOf(loser);

        List<Result> results = runTogether(List.of(
                () -> attempt(winner, slotId),
                () -> attempt(loser, slotId)));

        Result loserResult = results.get(1);
        if (!loserResult.succeeded()) {
            assertEquals(loserBefore, wallets.balanceOf(loser), "실패한 임대는 코인을 쓰지 않습니다.");
            assertTrue(leases.findValidByLesseeUserId(loser, Instant.now()).isEmpty());
        }
        assertBalanceMatchesLedger(wallets, loser);
    }

    @RepeatedTest(REPEATS)
    void aMemberDoubleClickingLeasesOnceAndPaysOnce() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "더블클릭");
        Long slotId = freeSlotId();
        int before = wallets.balanceOf(userId);

        runTogether(List.of(
                () -> attempt(userId, slotId),
                () -> attempt(userId, slotId)));

        assertEquals(before - properties.priceCoin(), wallets.balanceOf(userId),
                "코인은 한 번만 차감돼야 합니다.");
        assertEquals(1, leases.findAllValid(Instant.now()).stream()
                .filter(lease -> lease.getSlotId().equals(slotId)).count());
        assertBalanceMatchesLedger(wallets, userId);
    }

    private Result attempt(Long userId, Long slotId) {
        try {
            leaseService.lease(userId, slotId, 1);
            return new Result(true);
        } catch (RuntimeException exception) {
            return new Result(false);
        }
    }

    private List<Result> runTogether(List<Supplier<Result>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Supplier<Result> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.get();
                }));
            }
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Long freeSlotId() {
        Instant now = Instant.now();
        return slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), now).isEmpty())
                .map(BoothSlot::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("빈 USER_RENTAL 슬롯이 없습니다."));
    }

    private record Result(boolean succeeded) {
    }
}
