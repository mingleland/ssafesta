package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lease expiry pass (S15P21A604-152, docs/08 "Lease 만료 처리 계약").
 *
 * <p>Two things here are not about the happy path. The pass runs against rows nobody asked about,
 * so it can meet a re-lease of the same slot or of the same member in flight — the concurrent cases
 * are what say that neither leaves a slot double-booked nor a booth holding a valid lease and no
 * slot. And the transaction has to cover both rows: an {@code EXPIRED} lease whose booth still
 * points at the slot is exactly the state FR-017 says makes re-leasing impossible.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothLeaseExpirySweeperIntegrationTest {

    /** Racing cases only. A race that passed once has not been shown to be closed. */
    private static final int REPEATS = 5;

    @Autowired private BoothLeaseExpirySweeper sweeper;
    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothQueryService queries;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Value("${app.lease.expiry-scan-interval}")
    private Duration scanInterval;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @AfterEach
    void detachAppender() {
        if (logs != null) {
            serviceLogger().detachAppender(logs);
            logs = null;
        }
    }

    /**
     * The fixture every other booth test uses — pushing {@code ends_at} into the past — is only
     * stable while no live schedule is rewriting those rows underneath it. Asserted rather than
     * assumed: a property this class does not own would otherwise turn into intermittent failures
     * somewhere else entirely.
     */
    @Test
    void theScheduleDoesNotRunDuringTests() {
        assertTrue(scanInterval.toHours() >= 24,
                "테스트에서 만료 배치가 스케줄로 돌면 만료 픽스처가 간헐 실패합니다.");
    }

    @Test
    void aStaleLeaseIsTransitionedAndItsSlotReleased() {
        Long userId = createMemberWithWallet(users, wallets, "스윕");
        Long slotId = freeSlotIds(1).get(0);
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        endLease(lease.getId());

        sweeper.expireStaleLeases();

        assertEquals(LeaseStatus.EXPIRED, leases.findById(lease.getId()).orElseThrow().getStatus());
        assertNull(booths.findById(lease.getBoothId()).orElseThrow().getCurrentSlotId(),
                "만료된 임대의 슬롯 연결은 남지 않아야 합니다 (FR-017).");
        assertEquals("AVAILABLE", slotView(slotId).status());
    }

    @Test
    void aLeaseThatStillHasTimeIsLeftAlone() {
        Long userId = createMemberWithWallet(users, wallets, "유효");
        Long slotId = freeSlotIds(1).get(0);
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();

        sweeper.expireStaleLeases();

        assertEquals(LeaseStatus.ACTIVE, leases.findById(lease.getId()).orElseThrow().getStatus());
        assertEquals(slotId, booths.findById(lease.getBoothId()).orElseThrow().getCurrentSlotId());
    }

    /**
     * Both halves in one transaction (docs/08). Rolling the caller back has to take the slot release
     * with it — a lease left {@code EXPIRED} whose booth still holds {@code current_slot_id} is the
     * state that makes the slot permanently unleasable.
     */
    @Test
    void aRolledBackPassLeavesBothRowsUntouched() {
        Long userId = createMemberWithWallet(users, wallets, "롤백");
        Long slotId = freeSlotIds(1).get(0);
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        endLease(lease.getId());

        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                leaseService.expireStaleLeases();
                throw new IllegalStateException("의도된 롤백");
            });
        } catch (IllegalStateException expected) {
            // 롤백 자체가 단정 대상이다 — 아래 두 줄이 그것을 본다.
        }

        assertEquals(LeaseStatus.ACTIVE, leases.findById(lease.getId()).orElseThrow().getStatus());
        assertEquals(slotId, booths.findById(lease.getBoothId()).orElseThrow().getCurrentSlotId());
    }

    /** The cap bounds one transaction; what it leaves is the next pass's work, not lost work. */
    @Test
    void onePassTakesAtMostTheBatchAndTheNextTakesTheRest() {
        List<Long> slotIds = freeSlotIds(6);
        List<Long> leaseIds = new ArrayList<>();
        for (Long slotId : slotIds) {
            Long userId = createMemberWithWallet(users, wallets, "상한");
            BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
            endLease(lease.getId());
            leaseIds.add(lease.getId());
        }

        assertEquals(5, leaseService.expireStaleLeases(), "한 주기는 상한까지만 처리해야 합니다.");
        assertEquals(1, leaseService.expireStaleLeases(), "남은 것은 다음 주기가 가져가야 합니다.");
        assertEquals(0, leaseService.expireStaleLeases());

        for (Long leaseId : leaseIds) {
            assertEquals(LeaseStatus.EXPIRED, leases.findById(leaseId).orElseThrow().getStatus());
        }
    }

    /**
     * The lazy paths in {@link BoothLeaseService} and the pass share one expiry method, so that
     * S15P21A604-496 has one place to attach the AI transitions spec 007 FR-041 wants in the same
     * transaction. Asserted through the log line that method owns: two different callers, one
     * message.
     */
    @Test
    void theLazyPathAndThePassGoThroughOneExpiryMethod() {
        captureServiceLogs();

        Long previous = createMemberWithWallet(users, wallets, "지연");
        Long next = createMemberWithWallet(users, wallets, "재임대");
        Long lazySlot = freeSlotIds(1).get(0);
        Long lazyLeaseId = leaseService.lease(previous, lazySlot, 1).lease().getId();
        endLease(lazyLeaseId);
        // 재임대 요청이 낡은 행을 전이시키는 경로다.
        leaseService.lease(next, lazySlot, 1);

        Long swept = createMemberWithWallet(users, wallets, "배치");
        Long sweptSlot = freeSlotIds(1).get(0);
        Long sweptLeaseId = leaseService.lease(swept, sweptSlot, 1).lease().getId();
        endLease(sweptLeaseId);
        sweeper.expireStaleLeases();

        List<String> expiryMessages = logs.list.stream()
                .map(ILoggingEvent::getMessage)
                .filter(message -> message.startsWith("만료 임대 정리"))
                .toList();
        assertEquals(List.of("만료 임대 정리 — leaseId={}, slotId={}, boothId={}",
                        "만료 임대 정리 — leaseId={}, slotId={}, boothId={}"), expiryMessages,
                "두 경로가 같은 만료 메서드를 지나야 합니다.");
    }

    @RepeatedTest(REPEATS)
    void twoPassesRunningTogetherExpireOneLeaseOnce() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "동시배치");
        Long slotId = freeSlotIds(1).get(0);
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        endLease(lease.getId());

        List<Integer> expired = runTogether(List.of(
                () -> leaseService.expireStaleLeases(),
                () -> leaseService.expireStaleLeases()));

        assertEquals(1, expired.get(0) + expired.get(1), "만료는 한 번만 일어나야 합니다.");
        assertEquals(LeaseStatus.EXPIRED, leases.findById(lease.getId()).orElseThrow().getStatus());
    }

    /**
     * The pass and a re-lease of the same slot by someone else. Whoever wins, the slot ends up held
     * by exactly one booth and the old tenant holds none — {@code ux_booth_leases_active_slot} and
     * {@code booths.current_slot_id} are what make a double booking impossible, and the pass must
     * not be the thing that trips them.
     */
    @RepeatedTest(REPEATS)
    void aPassMeetingAReLeaseOfTheSameSlotNeverDoubleBooksIt() throws Exception {
        Long previous = createMemberWithWallet(users, wallets, "기존");
        Long next = createMemberWithWallet(users, wallets, "신규");
        Long slotId = freeSlotIds(1).get(0);
        BoothLease stale = leaseService.lease(previous, slotId, 1).lease();
        endLease(stale.getId());

        runTogether(List.of(
                () -> leaseService.expireStaleLeases(),
                () -> attempt(next, slotId)));

        assertEquals(LeaseStatus.EXPIRED, leases.findById(stale.getId()).orElseThrow().getStatus());
        assertNull(booths.findById(stale.getBoothId()).orElseThrow().getCurrentSlotId(),
                "만료된 임대의 부스는 슬롯을 놓아야 합니다.");
        leases.findValidBySlotId(slotId, Instant.now()).ifPresent(held -> {
            assertEquals(next, held.getLesseeUserId());
            assertEquals(slotId, booths.findById(held.getBoothId()).orElseThrow().getCurrentSlotId(),
                    "유효한 임대의 부스는 그 슬롯을 가리켜야 합니다.");
        });
    }

    /**
     * The pass and the same member leasing again, somewhere else. Their booth is one row across
     * leases (C-01), so this is where a pass that detached unconditionally would clear the pointer
     * of a lease that is perfectly valid — and, through {@link Booth#detachSlot}, its published
     * layout version with it.
     */
    @RepeatedTest(REPEATS)
    void aPassMeetingTheSameMembersNextLeaseNeverLosesTheirSlot() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "이사");
        List<Long> slotIds = freeSlotIds(2);
        BoothLease stale = leaseService.lease(userId, slotIds.get(0), 1).lease();
        endLease(stale.getId());

        List<Integer> results = runTogether(List.of(
                () -> leaseService.expireStaleLeases(),
                () -> attempt(userId, slotIds.get(1))));

        Booth booth = booths.findById(stale.getBoothId()).orElseThrow();
        if (results.get(1) == 1) {
            BoothLease held = leases.findValidByLesseeUserId(userId, Instant.now()).orElseThrow();
            assertEquals(slotIds.get(1), held.getSlotId());
            assertEquals(slotIds.get(1), booth.getCurrentSlotId(),
                    "새 임대가 성립했으면 부스는 그 슬롯을 가리켜야 합니다.");
        } else {
            assertNull(booth.getCurrentSlotId());
        }
    }

    /**
     * The same shape without the race: a booth already pointing at another slot while its old lease
     * is still {@code ACTIVE} and past its end. The pass transitions the lease and leaves the
     * pointer alone, because the connection it would clear is not the one this lease is about.
     *
     * <p>Seeded by JDBC on purpose. Reaching this state through the API needs the interleaving the
     * two tests above chase, and a guard only a race can exercise is a guard nothing pins down.
     */
    @Test
    void aPassDoesNotDetachABoothThatHasAlreadyMovedOn() {
        Long userId = createMemberWithWallet(users, wallets, "이동");
        List<Long> slotIds = freeSlotIds(2);
        BoothLease stale = leaseService.lease(userId, slotIds.get(0), 1).lease();
        endLease(stale.getId());
        jdbc.update("UPDATE booths SET current_slot_id = ? WHERE id = ?", slotIds.get(1),
                stale.getBoothId());

        sweeper.expireStaleLeases();

        assertEquals(LeaseStatus.EXPIRED, leases.findById(stale.getId()).orElseThrow().getStatus());
        assertEquals(slotIds.get(1), booths.findById(stale.getBoothId()).orElseThrow().getCurrentSlotId(),
                "이 임대의 슬롯이 아닌 연결은 이 임대의 만료가 건드릴 것이 아닙니다.");
    }

    /**
     * @return 1 if the lease was granted, 0 if it lost the race. Only the two racing outcomes are
     *     swallowed — anything else is a failure this class wants to see, not a zero
     */
    /**
     * An edit that started while the lease was still valid must not put the slot connection back
     * when it commits after the pass.
     *
     * <p>This is the one window the guard in {@code BoothLeaseService.expire} cannot see: the
     * reviving write is not the pass, it is the other transaction. {@code BoothHomepageService} and
     * {@code BoothFacadeService} reach a booth through {@code BoothAccessGuard.requireActiveEditor},
     * which takes no row lock and admits the owner right up to the instant the lease runs out. Their
     * flush then writes {@code current_slot_id} back from the snapshot they read — a booth row
     * claiming a slot and a published layout while its lease says {@code EXPIRED}, and a pass that
     * logged a cleanup which is no longer in the database.
     *
     * <p>Sequenced rather than raced: the editor loads the booth, the pass commits, and only then
     * does the editor write. A latch is the only way to pin an ordering that a race would hit
     * sometimes and CI would then fail on some other day.
     */
    @Test
    void anEditCommittingAfterThePassDoesNotReviveTheSlotConnection() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "동시수정");
        Long slotId = freeSlotIds(1).get(0);
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        endLease(lease.getId());

        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch swept = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> editor = pool.submit(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        Booth booth = booths.findById(lease.getBoothId()).orElseThrow();
                        loaded.countDown();
                        awaitQuietly(swept);
                        booth.changeHomepageUrl("https://revive.example.com");
                    }));
            assertTrue(loaded.await(30, TimeUnit.SECONDS));
            sweeper.expireStaleLeases();
            swept.countDown();
            editor.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Booth booth = booths.findById(lease.getBoothId()).orElseThrow();
        assertNull(booth.getCurrentSlotId(), "만료로 끊은 슬롯 연결이 나중 커밋으로 되살아나면 안 됩니다.");
        assertNull(booth.getPublishedLayoutVersion());
        assertEquals("https://revive.example.com", booth.getHomepageUrl(),
                "수정 자체는 반영돼야 합니다 — 되살리지 않는 것과 수정을 잃는 것은 다릅니다.");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("상대 스레드를 기다리다 시간이 지났습니다.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private int attempt(Long userId, Long slotId) {
        try {
            leaseService.lease(userId, slotId, 1);
            return 1;
        } catch (SlotAlreadyLeasedException | ActiveLeaseLimitException lost) {
            return 0;
        }
    }

    /**
     * Releases the tasks together and <b>lets anything they throw out</b>. Swallowing here would
     * turn a deadlock or a constraint error in the pass into a plain "expired nothing", which the
     * sum assertions read as success — the one expected failure, losing a lease race, is caught in
     * {@link #attempt} by its own two exception types instead.
     */
    private List<Integer> runTogether(List<Supplier<Integer>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Supplier<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.get();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Pushes both ends into the past — {@code CHECK(ends_at > starts_at)} forbids moving one. */
    private void endLease(Long leaseId) {
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE id = ?", leaseId);
    }

    private List<Long> freeSlotIds(int count) {
        Instant now = Instant.now();
        List<Long> free = slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), now).isEmpty())
                .filter(slot -> booths.findByCurrentSlotId(slot.getId()).isEmpty())
                .map(BoothSlot::getId)
                .limit(count)
                .toList();
        assertEquals(count, free.size(), "빈 USER_RENTAL 슬롯이 부족합니다.");
        return free;
    }

    private BoothQueryService.SlotView slotView(Long slotId) {
        return queries.listSlots(null).stream()
                .filter(slot -> slot.slotId().equals(slotId)).findFirst().orElseThrow();
    }

    private void captureServiceLogs() {
        logs = new ListAppender<>();
        logs.start();
        serviceLogger().addAppender(logs);
    }

    private Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(BoothLeaseService.class);
    }
}
