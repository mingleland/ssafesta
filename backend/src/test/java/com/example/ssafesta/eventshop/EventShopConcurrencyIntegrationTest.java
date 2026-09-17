package com.example.ssafesta.eventshop;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Concurrency guarantees for the event shop's purchase path (S15P21A604-836).
 *
 * <p>Same shape as {@code BoothLeaseConcurrencyIntegrationTest} — real threads released together
 * by a latch, repeated because a race that passes once has not been shown to be closed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EventShopConcurrencyIntegrationTest {

    private static final int REPEATS = 5;

    @Autowired private EventShopService shop;
    @Autowired private EventPrizeRepository prizes;
    @Autowired private EventPurchaseRepository purchases;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;

    /** The last unit of stock must go to exactly one of two simultaneous buyers, not both. */
    @RepeatedTest(REPEATS)
    void twoBuyersRacingForTheLastUnitProduceExactlyOnePurchase() throws Exception {
        EventPrize prize = prizes.saveAndFlush(new EventPrize("경합용품", 10, 1));
        Long buyerA = createMemberWithWallet(users, wallets, "경합구매A");
        Long buyerB = createMemberWithWallet(users, wallets, "경합구매B");

        List<Result> results = runTogether(List.of(
                () -> attempt(buyerA, prize.getId(), UUID.randomUUID().toString()),
                () -> attempt(buyerB, prize.getId(), UUID.randomUUID().toString())));

        assertEquals(1, results.stream().filter(Result::succeeded).count(),
                "재고 1개는 한 명에게만 팔려야 합니다.");
        assertEquals(1, results.stream().filter(result -> !result.succeeded()).count());
        assertEquals(0, (int) prizes.findById(prize.getId()).orElseThrow().getStock());
        assertEquals(1, purchases.findAll().stream()
                .filter(p -> p.getPrizeId().equals(prize.getId())).count());
    }

    /**
     * The exact race the idempotency reordering fixed: two truly concurrent requests carrying the
     * same key must produce one purchase and one charge, not a constraint-violation 500 on the
     * loser.
     */
    @RepeatedTest(REPEATS)
    void aBuyerDoubleClickingBuysOnceAndPaysOnce() throws Exception {
        EventPrize prize = prizes.saveAndFlush(new EventPrize("더블클릭용품", 30, 5));
        Long buyer = createMemberWithWallet(users, wallets, "더블클릭자");
        int before = wallets.balanceOf(buyer);
        String sameKey = UUID.randomUUID().toString();

        List<Result> results = runTogether(List.of(
                () -> attempt(buyer, prize.getId(), sameKey),
                () -> attempt(buyer, prize.getId(), sameKey)));

        assertEquals(2, results.stream().filter(Result::succeeded).count(),
                "같은 키의 재시도는 실패가 아니라 같은 결과의 반복이어야 합니다.");
        assertEquals(before - 30, wallets.balanceOf(buyer), "코인은 한 번만 차감돼야 합니다.");
        assertEquals(4, (int) prizes.findById(prize.getId()).orElseThrow().getStock(),
                "재고는 한 번만 차감돼야 합니다.");
        assertEquals(1, purchases.findAll().stream()
                .filter(p -> p.getPrizeId().equals(prize.getId())).count(),
                "구매 행은 하나여야 합니다.");
    }

    private Result attempt(Long userId, Long prizeId, String operationId) {
        try {
            shop.purchase(userId, prizeId, 1, operationId);
            return new Result(true);
        } catch (ApiException refused) {
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

    private record Result(boolean succeeded) {
    }
}
