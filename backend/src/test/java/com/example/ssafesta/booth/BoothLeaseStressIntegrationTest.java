package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Load-shaped concurrency scenarios (spec 004 SC-001/SC-002).
 *
 * <p>{@link BoothLeaseConcurrencyIntegrationTest} shows the two-thread race is closed. This class
 * asks whether the same guarantees hold under real contention: a hundred members on one slot, and
 * one member clicking as fast as a mouse allows. Slow on purpose - tagged {@code stress} so it can
 * be excluded from the fast build.
 */
@Tag("stress")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLeaseStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BoothLeaseStressIntegrationTest.class);

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private LeaseProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    // ---------------------------------------------------------------- scenario 1

    /**
     * 100 members race for one slot, 100 rounds - 10,000 lease attempts.
     *
     * <p>Every round must produce exactly one lease and charge exactly one member.
     */
    @Test
    void hundredMembersRacingForOneSlotOverAHundredRounds() throws Exception {
        int rounds = 100;
        int members = 100;

        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            Long userId = createMemberWithWallet(users, wallets, "부하");
            fund(userId, 50_000);
            userIds.add(userId);
        }
        Map<Long, Integer> startingBalance = new LinkedHashMap<>();
        userIds.forEach(id -> startingBalance.put(id, wallets.balanceOf(id)));

        List<Long> rentable = rentableSlotIds();
        Map<Long, Integer> wins = new ConcurrentHashMap<>();
        Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();
        long startedAt = System.nanoTime();

        for (int roundIndex = 0; roundIndex < rounds; roundIndex++) {
            final int round = roundIndex;
            BoothTestSupport.releaseAllSlots(jdbc);
            Long slotId = rentable.get(round % rentable.size());

            List<Attempt> results = runTogether(userIds.stream()
                    .map(userId -> (Supplier<Attempt>) () -> attempt(userId, slotId, outcomes))
                    .toList());

            List<Attempt> winners = results.stream().filter(Attempt::succeeded).toList();
            assertEquals(1, winners.size(),
                    "라운드 " + round + ": 한 슬롯은 정확히 한 명에게만 임대돼야 합니다.");
            wins.merge(winners.get(0).userId(), 1, Integer::sum);

            BoothLease held = leases.findValidBySlotId(slotId, Instant.now()).orElseThrow(
                    () -> new AssertionError("라운드 " + round + ": 슬롯에 유효한 임대가 없습니다."));
            assertEquals(winners.get(0).userId(), held.getLesseeUserId(),
                    "라운드 " + round + ": 임대 보유자가 성공한 요청자와 같아야 합니다.");
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        log.info("시나리오1 - {}라운드 x {}스레드 = {}건, 소요 {}ms, 결과 분포 {}",
                rounds, members, rounds * members, elapsed.toMillis(), describe(outcomes));

        assertEquals(rounds, wins.values().stream().mapToInt(Integer::intValue).sum(),
                "성공 횟수 합계는 라운드 수와 같아야 합니다.");
        for (Long userId : userIds) {
            int expected = startingBalance.get(userId) - wins.getOrDefault(userId, 0) * properties.priceCoin();
            assertEquals(expected, wallets.balanceOf(userId),
                    "이긴 횟수만큼만 차감돼야 합니다 - userId=" + userId);
            assertBalanceMatchesLedger(wallets, userId);
        }
    }

    // ---------------------------------------------------------------- scenario 2

    /** One member hammering the same slot: 50 simultaneous clicks, 30 rounds. */
    @Test
    void oneMemberSpammingTheSameSlot() throws Exception {
        int rounds = 30;
        int clicks = 50;
        Long userId = createMemberWithWallet(users, wallets, "광클동일");
        fund(userId, 50_000);
        List<Long> rentable = rentableSlotIds();
        Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();

        for (int round = 0; round < rounds; round++) {
            BoothTestSupport.releaseAllSlots(jdbc);
            Long slotId = rentable.get(round % rentable.size());
            int before = wallets.balanceOf(userId);

            runTogether(repeat(clicks, () -> attempt(userId, slotId, outcomes)));

            assertEquals(1, validLeaseCountOf(userId),
                    "라운드 " + round + ": 광클해도 임대는 한 건이어야 합니다.");
            assertEquals(before - properties.priceCoin(), wallets.balanceOf(userId),
                    "라운드 " + round + ": 코인은 한 번만 차감돼야 합니다.");
            assertBalanceMatchesLedger(wallets, userId);
        }
        log.info("시나리오2a - 같은 슬롯 광클 {}회 x {}라운드, 결과 분포 {}", clicks, rounds, describe(outcomes));
    }

    /**
     * One member hammering <b>every</b> slot at once - the rage-click that spreads across the slot
     * list. The one-active-lease rule (D01, FR-005) has no database constraint behind it, only the
     * pre-check in {@link BoothLeaseService}, so this is where it would show.
     */
    @Test
    void oneMemberSpammingEverySlotAtOnce() throws Exception {
        int rounds = 30;
        Long userId = createMemberWithWallet(users, wallets, "광클전체");
        fund(userId, 50_000);
        List<Long> rentable = rentableSlotIds();
        Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();

        for (int roundIndex = 0; roundIndex < rounds; roundIndex++) {
            final int round = roundIndex;
            BoothTestSupport.releaseAllSlots(jdbc);
            int before = wallets.balanceOf(userId);

            runTogether(rentable.stream()
                    .map(slotId -> (Supplier<Attempt>) () -> attempt(userId, slotId, outcomes))
                    .toList());

            long held = validLeaseCountOf(userId);
            int spent = before - wallets.balanceOf(userId);
            assertBalanceMatchesLedger(wallets, userId);
            log.info("시나리오2b round={} 보유임대={} 차감코인={} 부스={}",
                    round, held, spent, boothCountOf(userId));
            assertEquals(1, held, "라운드 " + round + ": 회원 한 명은 임대를 하나만 가질 수 있습니다 (D01).");
            assertEquals(properties.priceCoin(), spent, "라운드 " + round + ": 차감은 한 건이어야 합니다.");
            // Before V7 each of the seven threads created its own booth. findByOwnerUserId returns
            // Optional, so the second row would not just be untidy — it would throw and lock the
            // member out of leasing entirely (C-01, invariant I-5).
            assertEquals(1, boothCountOf(userId), "라운드 " + round + ": 회원의 부스는 하나여야 합니다 (C-01).");
            booths.findByOwnerUserId(userId).orElseThrow(
                    () -> new AssertionError("라운드 " + round + ": 소유 부스를 단건으로 읽을 수 있어야 합니다."));
        }
        log.info("시나리오2b - 슬롯 {}개 동시 광클 x {}라운드, 결과 분포 {}", rentable.size(), rounds, describe(outcomes));
    }

    /** Rapid but sequential clicks - the idempotent retry path (FR-018). */
    @Test
    void oneMemberClickingRepeatedlyInSequence() {
        Long userId = createMemberWithWallet(users, wallets, "연타");
        fund(userId, 50_000);
        Long slotId = rentableSlotIds().get(0);
        int before = wallets.balanceOf(userId);

        for (int i = 0; i < 20; i++) {
            leaseService.lease(userId, slotId, 1);
        }

        assertEquals(1, validLeaseCountOf(userId));
        assertEquals(before - properties.priceCoin(), wallets.balanceOf(userId));
        assertBalanceMatchesLedger(wallets, userId);
    }

    // ---------------------------------------------------------------- helpers

    private void fund(Long userId, int amount) {
        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.CHARGE, amount,
                CoinReason.ADMIN_ADJUSTMENT, "STRESS_TEST", String.valueOf(userId),
                "STRESS_FUND:" + userId));
    }

    private long boothCountOf(Long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM booths WHERE owner_user_id = ?", Long.class, userId);
    }

    private long validLeaseCountOf(Long userId) {
        return leases.findAllValid(Instant.now()).stream()
                .filter(lease -> lease.getLesseeUserId().equals(userId))
                .count();
    }

    private List<Long> rentableSlotIds() {
        return slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .map(BoothSlot::getId)
                .toList();
    }

    private Attempt attempt(Long userId, Long slotId, Map<String, AtomicLong> outcomes) {
        try {
            leaseService.lease(userId, slotId, 1);
            count(outcomes, "SUCCESS");
            return new Attempt(userId, slotId, true);
        } catch (RuntimeException exception) {
            count(outcomes, exception.getClass().getSimpleName());
            return new Attempt(userId, slotId, false);
        }
    }

    private static void count(Map<String, AtomicLong> outcomes, String key) {
        outcomes.computeIfAbsent(key, unused -> new AtomicLong()).incrementAndGet();
    }

    private static String describe(Map<String, AtomicLong> outcomes) {
        return new TreeMap<>(outcomes).toString();
    }

    private static List<Supplier<Attempt>> repeat(int count, Supplier<Attempt> task) {
        List<Supplier<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(task);
        }
        return tasks;
    }

    private List<Attempt> runTogether(List<Supplier<Attempt>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Supplier<Attempt> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.get();
                }));
            }
            start.countDown();
            List<Attempt> results = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Attempt(Long userId, Long slotId, boolean succeeded) {
    }
}
