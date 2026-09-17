package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency guarantees (spec 004 User Story 2, SC-001/SC-002).
 *
 * <p>Repeated on purpose: a race that passes once has not been shown to be closed. Threads are
 * released together by a latch so the requests really overlap.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothLeaseConcurrencyIntegrationTest {

    private static final int REPEATS = 5;

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private BoothRepository booths;
    @Autowired private TransactionTemplate transactions;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private LeaseProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    /**
     * 반납이 임대 행을 먼저 잠그면 만료 배치가 그 행을 건너뛴다 (spec 004 D12 Edge Case).
     *
     * <p><b>시계를 건드리지 않는다.</b> {@code findStaleActive} 가 판정 시각을 인자로 받으므로
     * 아직 유효한 임대를 <b>미래 시각</b>으로 조회하면 배치 입장에서는 만료된 행으로 보인다.
     * 덕분에 {@code Clock} 주입 없이 경합을 고정할 수 있다.
     *
     * <p>순차 테스트로는 이것을 증명할 수 없다 — {@code findActiveByLesseeUserIdForUpdate} 의
     * {@code @Lock} 을 지워도 순차 시나리오는 그대로 통과한다.
     */
    @RepeatedTest(REPEATS)
    void aReturnThatLocksFirstMakesTheSweeperPassTheRowOver() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "선점반납");
        Long slotId = freeSlotId();
        Long leaseId = leaseService.lease(userId, slotId, 1).lease().getId();
        Instant afterEnd = leases.findById(leaseId).orElseThrow().getEndsAt().plusSeconds(60);

        CountDownLatch sweeperDone = new CountDownLatch(1);
        List<BoothLease> seenBySweeper = new ArrayList<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                leaseService.cancel(userId, slotId);
                // 아직 커밋 전이다. 이 상태에서 배치가 같은 행을 집으려 하면 SKIP LOCKED 로 건너뛴다.
                pool.submit(() -> {
                    try {
                        transactions.executeWithoutResult(inner ->
                                seenBySweeper.addAll(leases.findStaleActive(afterEnd, 5)));
                    } finally {
                        sweeperDone.countDown();
                    }
                });
                awaitQuietly(sweeperDone, pool);
            });
        } finally {
            sweeperDone.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "배치 스레드가 끝나야 합니다.");
        }

        assertTrue(seenBySweeper.stream().noneMatch(l -> l.getId().equals(leaseId)),
                "반납이 잠근 행은 배치가 건너뛰어야 합니다.");
        assertEquals(LeaseStatus.CANCELLED, leases.findById(leaseId).orElseThrow().getStatus());
        assertEquals(null, booths.findByOwnerUserId(userId).orElseThrow().getCurrentSlotId());
    }

    /**
     * 만료 배치가 먼저 잠그면 반납은 락이 풀릴 때까지 기다렸다가 거절된다 (spec 004 D12 Edge Case).
     *
     * <p>배치 쪽은 {@code lease.expire()} 가 아니라 <b>실제 {@link BoothLeaseService#expireStaleLeases()}</b>
     * 를 부른다 — 엔티티 메서드는 상태만 뒤집고 슬롯 해제·AI 문서 비활성화는 서비스의 공용 해제에
     * 있어서, 그것을 건너뛰면 아래 단언이 증명하는 것이 없어진다.
     */
    @RepeatedTest(REPEATS)
    void aSweeperThatLocksFirstMakesTheReturnWaitAndThenRefusesIt() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "선점만료");
        Long slotId = freeSlotId();
        Long leaseId = leaseService.lease(userId, slotId, 1).lease().getId();
        // 시계 대신 데이터로 만료시킨다 — 배치가 이 행을 집을 수 있게 한다.
        // starts_at 도 함께 민다 — booth_leases 에 CHECK(ends_at > starts_at) 가 있다 (V1).
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '2 minutes',"
                + " ends_at = now() - interval '1 minute' WHERE id = ?", leaseId);

        CountDownLatch returnStarted = new CountDownLatch(1);
        // 배치가 행을 잠근 뒤에야 반납을 출발시킨다. 이 래치가 없으면 두 스레드가 그냥 경주하고,
        // 반납이 먼저 잠그면 findStaleActive 의 SKIP LOCKED 가 그 행을 건너뛰어 배치가 0 을
        // 돌려준다 — 이 테스트가 고정하려는 것과 정반대의 순서이며, 그쪽은 쌍둥이 테스트
        // aReturnThatLocksFirstMakesTheSweeperPassTheRowOver 가 이미 덮는다 (S15P21A604-794).
        CountDownLatch sweeperLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Throwable> refusal;
        try {
            refusal = pool.submit(() -> {
                assertTrue(sweeperLocked.await(10, TimeUnit.SECONDS),
                        "배치가 행을 잠그기 전에 반납이 출발했습니다.");
                returnStarted.countDown();
                try {
                    leaseService.cancel(userId, slotId);
                    return null;
                } catch (Throwable caught) {
                    return caught;
                }
            });
            transactions.executeWithoutResult(status -> {
                assertEquals(1, leaseService.expireStaleLeases());
                sweeperLocked.countDown();
                awaitQuietly(returnStarted, pool);
                // 배치가 행을 쥐고 있는 동안 반납은 끝나지 못한다.
                assertThrows(TimeoutException.class, () -> refusal.get(400, TimeUnit.MILLISECONDS),
                        "배치가 잠근 동안 반납이 통과하면 안 됩니다.");
            });
        } finally {
            // 위에서 실패해도 반납 스레드가 래치에 10초 매달리지 않게 한다.
            sweeperLocked.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "반납 스레드가 끝나야 합니다.");
        }

        assertTrue(refusal.get() instanceof ActiveLeaseNotFoundException,
                "배치가 이긴 뒤의 반납은 ACTIVE_LEASE_NOT_FOUND 여야 합니다 — 실제: " + refusal.get());
        assertEquals(LeaseStatus.EXPIRED, leases.findById(leaseId).orElseThrow().getStatus(),
                "진 쪽이 최종 상태를 덮어쓰면 안 됩니다.");
        assertEquals(null, booths.findByOwnerUserId(userId).orElseThrow().getCurrentSlotId());
    }

    /** 래치 대기 중 인터럽트가 나면 테스트를 멈춘다 — 조용히 지나가면 경합이 성립하지 않은 채 통과한다. */
    private static void awaitQuietly(CountDownLatch latch, ExecutorService pool) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "상대 스레드를 기다리다 시간이 지났습니다.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IllegalStateException(interrupted);
        }
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
