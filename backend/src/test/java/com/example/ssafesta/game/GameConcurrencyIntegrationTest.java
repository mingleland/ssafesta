package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Two tabs, one game (FR-025 · #48 §Draft 저장).
 *
 * <p>The single-threaded stale-revision test proves the <b>response</b> is right; it cannot prove the
 * write is. A read-then-write implementation passes it and still loses an edit when the two requests
 * interleave, because nothing serialises them.
 *
 * <p>Repeated because one green run of a concurrency test proves nothing — spec 004 learned that in
 * T-110, where the losing path simply never ran.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameConcurrencyIntegrationTest {

    private static final int WRITERS = 8;

    @Autowired private GameDraftService draftService;
    @Autowired private GamePublishService publishService;
    @Autowired private GameRepository games;
    @Autowired private GameDraftRepository drafts;
    @Autowired private GamePublishedVersionRepository published;
    @Autowired private UserRepository users;

    /**
     * The first save is a create, and {@code expectedRevision: 0} is what every tab sends. The
     * primary key decides; the rest are told the revision moved.
     */
    @RepeatedTest(5)
    void onlyOneOfTheConcurrentFirstSavesWins() throws Exception {
        Owner owner = owner("동시최초");

        Counts counts = raceSaves(owner, 0);

        assertEquals(1, counts.succeeded(), "최초 저장은 하나만 성공해야 합니다: " + counts);
        assertEquals(WRITERS - 1, counts.conflicted(), counts.toString());
        assertEquals(1, drafts.findById(owner.gameId()).orElseThrow().getRevision());
    }

    /** The same race on an existing draft: one conditional UPDATE matches, the others match no row. */
    @RepeatedTest(5)
    void onlyOneOfTheConcurrentUpdatesWins() throws Exception {
        Owner owner = owner("동시수정");
        draftService.save(owner.gameId(), owner.userId(), 0, saveBody(owner));

        Counts counts = raceSaves(owner, 1);

        assertEquals(1, counts.succeeded(), "같은 revision 을 든 저장은 하나만 성공해야 합니다: " + counts);
        assertEquals(WRITERS - 1, counts.conflicted(), counts.toString());
        assertEquals(2, drafts.findById(owner.gameId()).orElseThrow().getRevision(),
                "성공한 저장 하나만큼만 revision 이 올라야 합니다.");
    }

    /**
     * Concurrent Publish of one draft.
     *
     * <p>{@code UNIQUE(game_id, version_no)} is the backstop, but a version number computed as
     * "highest + 1" outside the guard would let two transactions pick the same number and one would
     * fail on the constraint rather than on the revision. Either way the invariant that matters is
     * the same: <b>no duplicate version, and the pointer agrees with what was appended.</b>
     */
    @RepeatedTest(5)
    void concurrentPublishesLeaveNoDuplicateVersion() throws Exception {
        Owner owner = owner("동시발행");
        draftService.save(owner.gameId(), owner.userId(), 0, saveBody(owner));

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(WRITERS);

        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            for (int i = 0; i < WRITERS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        publishService.publish(owner.gameId(), owner.userId(), 1);
                        succeeded.incrementAndGet();
                    } catch (Exception refusedOrRaced) {
                        // Revision conflict, constraint violation or a serialisation failure — all of
                        // them are "this one did not publish", which is what the count below asserts.
                        refused.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        }

        assertEquals(WRITERS, succeeded.get() + refused.get(), "모든 스레드가 끝나야 합니다.");
        assertTrue(succeeded.get() >= 1, "적어도 하나는 발행돼야 합니다.");
        int highest = published.highestVersionNo(owner.gameId());
        assertEquals(succeeded.get(), highest,
                "성공한 수와 최고 version 이 같아야 합니다 — 중복도 구멍도 없다는 뜻입니다.");
        assertEquals(highest, games.findById(owner.gameId()).orElseThrow().getPublishedVersion(),
                "포인터는 마지막으로 append 된 version 을 가리켜야 합니다.");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Counts raceSaves(Owner owner, int expectedRevision) throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(WRITERS);

        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            for (int i = 0; i < WRITERS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        draftService.save(owner.gameId(), owner.userId(), expectedRevision,
                                saveBody(owner));
                        succeeded.incrementAndGet();
                    } catch (GameRevisionConflictException expected) {
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

    /**
     * The <b>project</b> JSON, not the request envelope — the controller unwraps
     * {@code {expectedRevision, project}} and hands the service only the project.
     */
    private String saveBody(Owner owner) {
        ObjectNode project = GameTestSupport.validProjectFor(owner.gameId());
        return GameTestSupport.write(project);
    }

    private Owner owner(String prefix) {
        Long userId = GameTestSupport.createMember(users, prefix);
        Long gameId = games.save(new Game(userId, prefix + " 게임")).getId();
        return new Owner(userId, gameId);
    }

    private record Owner(Long userId, Long gameId) { }

    private record Counts(int succeeded, int conflicted) {

        @Override
        public String toString() {
            return "성공=" + succeeded + " 충돌=" + conflicted;
        }
    }
}
