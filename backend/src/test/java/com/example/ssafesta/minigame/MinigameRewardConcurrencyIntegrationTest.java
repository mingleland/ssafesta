package com.example.ssafesta.minigame;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.minigame.TimerStopService.SessionIssued;
import com.example.ssafesta.minigame.TimerStopService.SubmitCommand;
import com.example.ssafesta.minigame.TimerStopService.SubmitResult;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The two races the settlement has to survive (spec 014 SC-002, SC-003).
 *
 * <p>Both are closed by one rule and not two: {@code TimerStopService.submit} takes the member's
 * wallet row lock <b>before</b> it reads the session or sums the day, so every submission by one
 * member is serialized. Nothing in {@code minigame_sessions} is locked at all.
 *
 * <p><b>Only the cap test proves that lock.</b> Delete the {@code lockOwner} call and this class
 * fails 3/3 on {@link #twoSessionsFinishingAtOnceCannotBetweenThemCrossTheDailyCap} and stays green
 * on the other — because the ledger's idempotency key holds SC-002 on its own. That is the design
 * (two independent guards), so the same-session test is a check on the outcome, not on the lock.
 *
 * <p>Repeated, because a race that passed once has not been shown to be closed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "app.minigame.timer-stop.target-min-seconds=0.001",
        "app.minigame.timer-stop.target-max-seconds=0.002",
        "app.minigame.timer-stop.fail-margin-seconds=1.0"})
class MinigameRewardConcurrencyIntegrationTest {

    private static final int REPEATS = 3;

    @Autowired private TimerStopService timerStop;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private MinigameSessionRepository sessions;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private MinigameProperties properties;

    @RepeatedTest(REPEATS)
    void twoSimultaneousSubmissionsOfOneSessionPayOnce() throws Exception {
        Long userId = member("미니경쟁");
        SessionIssued issued = timerStop.issue(userId);
        int balanceBefore = wallets.balanceOf(userId);

        List<SubmitResult> results = runTogether(2, index -> () ->
                timerStop.submit(userId, issued.sessionId(), new SubmitCommand(issued.targetSeconds())));

        // Both callers see the same verdict, and both are told they were paid 5 — the loser is
        // replaying the winner's result, not reporting a second grant. Asserting "exactly one
        // non-zero rewardedCoins" would contradict the resubmission contract; the ledger and the
        // balance are what actually prove the payment happened once.
        assertEquals(2, results.size());
        assertEquals(results.get(0).tier(), results.get(1).tier());
        assertEquals(results.get(0).rewardedCoins(), results.get(1).rewardedCoins());
        assertEquals(results.get(0).accepted(), results.get(1).accepted());

        // findByIdempotencyKey is a UNIQUE lookup — a second row for this session could not exist
        // to be counted, and the balance below is what proves it was only paid once.
        assertTrue(ledger.findByIdempotencyKey(TimerStopService.rewardKey(issued.sessionId())).isPresent());
        assertEquals(balanceBefore + 5, wallets.balanceOf(userId), "지급은 1회분이어야 합니다.");
        assertEquals(1, sessions.findByNonce(issued.sessionId()).stream()
                .filter(session -> session.getRewardLedgerEntryId() != null).count());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @RepeatedTest(REPEATS)
    void twoSessionsFinishingAtOnceCannotBetweenThemCrossTheDailyCap() throws Exception {
        // SC-003. Read the day's total outside the lock and both submissions see 48, both grant 5,
        // and the day ends on 58 — the check-then-act the wallet lock exists to prevent.
        Long userId = member("미니한도경쟁");
        int cap = properties.dailyCapCoins();
        creditMinigame(userId, cap - 2);
        SessionIssued first = timerStop.issue(userId);
        SessionIssued second = timerStop.issue(userId);
        List<SessionIssued> issued = List.of(first, second);

        List<SubmitResult> results = runTogether(2, index -> () ->
                timerStop.submit(userId, issued.get(index).sessionId(),
                        new SubmitCommand(issued.get(index).targetSeconds())));

        int granted = results.stream().mapToInt(SubmitResult::rewardedCoins).sum();
        assertEquals(2, granted, "남은 한도를 넘겨 지급되면 안 됩니다.");
        assertEquals(cap, wallets.grantedTodayFor(userId, CoinReason.MINIGAME_REWARD));
        assertTrue(results.stream().allMatch(SubmitResult::dailyLimitReached));
        assertBalanceMatchesLedger(wallets, userId);
    }

    private <T> List<T> runTogether(int threads, TaskFactory<T> factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                Callable<T> task = factory.create(index);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface TaskFactory<T> {
        Callable<T> create(int index);
    }

    private void creditMinigame(Long userId, int coins) {
        String reference = "seed-" + UUID.randomUUID();
        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, coins,
                CoinReason.MINIGAME_REWARD, TimerStopService.REWARD_REFERENCE_TYPE, reference,
                CoinReason.MINIGAME_REWARD + ":" + TimerStopService.REWARD_REFERENCE_TYPE + ":" + reference));
    }

    private Long member(String prefix) {
        Long userId = createMember(users, prefix);
        wallets.openWallet(userId);
        return userId;
    }
}
