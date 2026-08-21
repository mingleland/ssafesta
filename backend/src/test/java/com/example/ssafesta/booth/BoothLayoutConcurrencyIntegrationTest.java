package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.saveRequest;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two editors, one draft (FR-014, invariant I-6).
 *
 * <p>Repeated because a single green run of a concurrency test proves nothing — spec 004 learned
 * that the expensive way in T-110, where the losing path simply never ran.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLayoutConcurrencyIntegrationTest {

    private static final int WRITERS = 8;

    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothLayoutDraftRepository drafts;
    @Autowired private BoothRepository booths;
    @Autowired private BoothStaffRepository staffs;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @RepeatedTest(5)
    void onlyOneOfTheConcurrentFirstSavesWins() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "동시최초");
        Long boothId = booths.save(new Booth(ownerId, "동시 최초 부스")).getId();

        Counts counts = raceSaves(boothId, editorsFor(boothId, ownerId), 0L);

        // The primary key decides; everyone else is told the revision moved.
        assertEquals(1, counts.succeeded(), "최초 저장은 하나만 성공해야 합니다.");
        assertEquals(WRITERS - 1, counts.conflicted(), "나머지는 전부 409여야 합니다: " + counts);
        assertEquals(1L, drafts.findById(boothId).orElseThrow().getRevision());
    }

    @RepeatedTest(5)
    void onlyOneOfTheConcurrentUpdatesWins() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "동시수정");
        Long boothId = booths.save(new Booth(ownerId, "동시 수정 부스")).getId();
        layouts.saveDraft(boothId, ownerId, saveRequest(0));

        Counts counts = raceSaves(boothId, editorsFor(boothId, ownerId), 1L);

        assertEquals(1, counts.succeeded(), "같은 revision을 든 저장은 하나만 성공해야 합니다.");
        assertEquals(WRITERS - 1, counts.conflicted(), counts.toString());
        assertEquals(2L, drafts.findById(boothId).orElseThrow().getRevision(),
                "성공한 저장 하나만큼만 revision이 올라야 합니다.");
    }

    /** Owner plus staff, so the race runs through the real authorisation path too. */
    private Long[] editorsFor(Long boothId, Long ownerId) {
        Long[] editors = new Long[WRITERS];
        editors[0] = ownerId;
        for (int index = 1; index < WRITERS; index++) {
            Long staffId = createMemberWithWallet(users, wallets, "직원" + index);
            staffs.save(new BoothStaff(boothId, staffId, "EDITOR"));
            editors[index] = staffId;
        }
        return editors;
    }

    private Counts raceSaves(Long boothId, Long[] editors, long expectedRevision) throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(editors.length);

        try (ExecutorService pool = Executors.newFixedThreadPool(editors.length)) {
            for (Long editor : editors) {
                pool.submit(() -> {
                    try {
                        start.await();
                        layouts.saveDraft(boothId, editor, saveRequest(expectedRevision));
                        succeeded.incrementAndGet();
                    } catch (LayoutRevisionConflictException expected) {
                        conflicted.incrementAndGet();
                    } catch (Exception unexpected) {
                        other.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        }

        assertEquals(0, other.get(), "예상치 못한 예외가 나오면 안 됩니다.");
        return new Counts(succeeded.get(), conflicted.get());
    }

    private record Counts(int succeeded, int conflicted) {

        @Override
        public String toString() {
            return "성공=" + succeeded + " 충돌=" + conflicted;
        }
    }
}
