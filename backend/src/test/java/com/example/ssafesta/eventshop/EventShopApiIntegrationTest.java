package com.example.ssafesta.eventshop;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The member-facing purchase flow (S15P21A604-836). Initial grant is 200 coins
 * (application.yml), so prices below stay well under it except where a case needs to exceed it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EventShopApiIntegrationTest {

    /** 받는 자 정보는 모든 구매에 필수다 (GitLab #239) — 본문마다 같은 세 필드를 얹는다. */
    private static final String RECIPIENT =
            ",\"campus\":\"대전\",\"teamName\":\"A604\",\"recipientName\":\"황덕\"";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private EventPrizeRepository prizes;
    @Autowired private EventPurchaseRepository purchases;
    @Autowired private EventShopService shop;
    @Autowired private EventPrizeClosingService closing;

    @Test
    void inactivePrizesAreExcludedFromTheList() throws Exception {
        Long prize = savePrize("목록노출", 10, null, true).getId();
        savePrize("목록숨김", 10, null, false);
        Long member = member("목록조회자");

        mockMvc.perform(get("/api/v1/event-shop/prizes").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prizes[?(@.prizeId == " + prize + ")]").exists())
                .andExpect(jsonPath("$.prizes[?(@.name == '목록숨김')]").doesNotExist());
    }

    @Test
    void purchaseChargesCoinsDecrementsStockAndRecordsThePurchase() throws Exception {
        EventPrize prize = savePrize("구매성공", 50, 3, true);
        Long member = member("구매자");
        String bearer = bearer(member);
        warmUpDailyGrant(bearer);
        int before = wallets.balanceOf(member);

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":2" + RECIPIENT + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prizeId").value(prize.getId()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.coinSpent").value(100))
                .andExpect(jsonPath("$.fulfillment").value("PURCHASED"));

        assertEquals(before - 100, wallets.balanceOf(member));
        assertEquals(1, (int) prizes.findById(prize.getId()).orElseThrow().getStock());
    }

    @Test
    void retryingTheSameIdempotencyKeyChargesOnlyOnce() throws Exception {
        EventPrize prize = savePrize("재시도", 40, null, true);
        Long member = member("재시도자");
        String bearer = bearer(member);
        warmUpDailyGrant(bearer);
        int before = wallets.balanceOf(member);
        String key = UUID.randomUUID().toString();
        String body = "{\"prizeId\":" + prize.getId() + ",\"quantity\":1" + RECIPIENT + "}";

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        assertEquals(before - 40, wallets.balanceOf(member));
    }

    @Test
    void sameKeyWithADifferentQuantityIsAConflictNotADoubleCharge() throws Exception {
        EventPrize prize = savePrize("키충돌", 30, null, true);
        Long member = member("키충돌자");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":2" + RECIPIENT + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void outOfStockAndInactiveAndUnknownPrizeAreRefusedWithoutCharging() throws Exception {
        EventPrize soldOut = savePrize("품절", 10, 1, true);
        EventPrize inactive = savePrize("중단", 10, null, false);
        Long member = member("실패자");
        String bearer = bearer(member);
        warmUpDailyGrant(bearer);
        int before = wallets.balanceOf(member);

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + soldOut.getId() + ",\"quantity\":2" + RECIPIENT + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_OUT_OF_STOCK"));

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + inactive.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_INACTIVE"));

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":999999999,\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_NOT_FOUND"));

        assertEquals(before, wallets.balanceOf(member));
        assertEquals(1, (int) prizes.findById(soldOut.getId()).orElseThrow().getStock());
    }

    @Test
    void insufficientCoinRefusesAndLeavesStockUntouched() throws Exception {
        EventPrize expensive = savePrize("비쌈", 500, 5, true);
        Long member = member("빈털터리");

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + expensive.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_COIN"));

        assertEquals(5, (int) prizes.findById(expensive.getId()).orElseThrow().getStock());
        assertTrue(purchases.findAll().stream().noneMatch(p -> p.getPrizeId().equals(expensive.getId())));
    }

    @Test
    void aPurchaseWithoutARecipientIsRefusedAndChargesNothing() throws Exception {
        EventPrize prize = savePrize("수령자없음", 20, 3, true);
        Long member = member("수령자누락");
        String bearer = bearer(member);
        warmUpDailyGrant(bearer);
        int before = wallets.balanceOf(member);

        // 세 필드가 아예 없다 — FE 가 붙기 전의 옛 본문 모양이다.
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("campus"));

        // 조 이름만 공백이다 — 있는 척하는 값도 같은 거절이다.
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1,\"campus\":\"대전\","
                                + "\"teamName\":\"   \",\"recipientName\":\"황덕\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("teamName"));

        assertEquals(before, wallets.balanceOf(member));
        assertEquals(3, (int) prizes.findById(prize.getId()).orElseThrow().getStock());
        assertTrue(purchases.findAll().stream().noneMatch(p -> p.getPrizeId().equals(prize.getId())));
    }

    /**
     * The controller is not the gate. A caller reaching the service directly — the raffle API is
     * planned on the same service — gets the same refusal (GitLab #239).
     */
    @Test
    void callingTheServiceWithoutARecipientIsRefusedToo() {
        EventPrize prize = savePrize("서비스직행", 20, 3, true);
        Long member = member("서비스직행자");

        ApiException refused = assertThrows(ApiException.class, () -> shop.purchase(member, prize.getId(), 1,
                null, UUID.randomUUID().toString()));

        assertEquals(ErrorCode.VALIDATION_FAILED, refused.errorCode());
        assertEquals(3, (int) prizes.findById(prize.getId()).orElseThrow().getStock());
    }

    @Test
    void aCampusOutsideTheListIsRefused() throws Exception {
        EventPrize prize = savePrize("캠퍼스오류", 20, 3, true);
        Long member = member("캠퍼스오류자");

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1,\"campus\":\"제주\","
                                + "\"teamName\":\"A604\",\"recipientName\":\"황덕\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("campus"));
    }

    @Test
    void theRecipientIsStoredWithThePurchaseAndTrimmed() throws Exception {
        EventPrize prize = savePrize("수령자저장", 20, 3, true);
        Long member = member("수령자저장자");

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1,\"campus\":\"부울경\","
                                + "\"teamName\":\" B105 \",\"recipientName\":\" 김수령 \"}"))
                .andExpect(status().isCreated());

        EventPurchase saved = purchases.findAll().stream()
                .filter(p -> p.getPrizeId().equals(prize.getId())).findFirst().orElseThrow();
        assertEquals(new PurchaseRecipient("부울경", "B105", "김수령"), saved.getRecipient());
    }

    @Test
    void sameKeyWithADifferentRecipientIsAConflict() throws Exception {
        EventPrize prize = savePrize("수령자충돌", 20, null, true);
        Long member = member("수령자충돌자");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1,\"campus\":\"서울\","
                                + "\"teamName\":\"A604\",\"recipientName\":\"다른사람\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }


    // ── 응모형 경품 (S15P21A604-922) ──────────────────────────────────

    @Test
    void aMemberMayEnterARaffleOnlyOnce() throws Exception {
        EventPrize raffle = savePrize("응모1회", 50, 100, true, null, 1);
        Long entrant = member("중복응모자");
        String bearer = bearer(entrant);
        warmUpDailyGrant(bearer);
        int before = wallets.balanceOf(entrant);
        String body = "{\"prizeId\":" + raffle.getId() + ",\"quantity\":1" + RECIPIENT + "}";

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // Idempotency-Key 가 다르다 — 재시도가 아니라 두 번째 응모다.
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_ALREADY_ENTERED"));

        assertEquals(before - 50, wallets.balanceOf(entrant));
    }

    @Test
    void aRaffleTakesOnlyOneTicketPerRequest() throws Exception {
        EventPrize raffle = savePrize("응모수량", 50, 100, true, null, 1);
        Long entrant = member("다량응모자");

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(entrant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + raffle.getId() + ",\"quantity\":2" + RECIPIENT + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void entriesPastTheDeadlineAreRefusedWhileThePrizeIsStillActive() throws Exception {
        // 스케줄러가 아직 돌지 않아 active 는 true 다 — 경계는 closesAt 이지 active 가 아니다.
        EventPrize closed = savePrize("마감된응모", 50, 100, true,
                Instant.now().minus(Duration.ofMinutes(1)), 1);
        Long entrant = member("지각응모자");
        String bearer = bearer(entrant);
        warmUpDailyGrant(bearer);
        int before = wallets.balanceOf(entrant);

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + closed.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_CLOSED"));

        assertEquals(before, wallets.balanceOf(entrant));
    }

    @Test
    void ticketsRunOutAtTheCap() throws Exception {
        EventPrize raffle = savePrize("응모권소진", 10, 2, true, null, 1);
        enter(raffle, "선착갑");
        enter(raffle, "선착을");

        Long late = member("선착병");
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(late))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + raffle.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_OUT_OF_STOCK"));
    }

    @Test
    void theSweepClosesAnExpiredRaffleAndDrawsExactlyOneWinner() throws Exception {
        EventPrize raffle = savePrize("추첨", 10, 100, true, null, 1);
        enter(raffle, "응모자갑");
        enter(raffle, "응모자을");
        enter(raffle, "응모자병");
        // 응모가 다 끍난 뒤에야 마감 시각을 과거로 밀어놓는다 — 앞서 밀면 응모 자체가 막힌다.
        raffle.update(raffle.getName(), raffle.getPriceCoin(), raffle.getStock(), true,
                Instant.now().minus(Duration.ofMinutes(1)), 1);
        prizes.saveAndFlush(raffle);

        Instant now = Instant.now();
        assertEquals(1, closing.closeExpiredPrizes(now));
        assertEquals(1, closing.drawPendingRaffles(now));

        EventPrize after = prizes.findById(raffle.getId()).orElseThrow();
        assertFalse(after.isActive());
        assertNotNull(after.getDrawnAt());
        assertEquals(1, countWon(raffle.getId(), true));
        assertEquals(2, countWon(raffle.getId(), false));
    }

    @Test
    void aSecondSweepDoesNotDrawAgain() throws Exception {
        EventPrize raffle = savePrize("재추첨금지", 10, 100, true, null, 1);
        enter(raffle, "재추첨응모자갑");
        enter(raffle, "재추첨응모자을");
        takeOffSale(raffle);

        assertEquals(1, closing.drawPendingRaffles(Instant.now()));
        List<Long> winnersBefore = winnerIdsOf(raffle.getId());
        assertEquals(1, winnersBefore.size());

        assertEquals(0, closing.drawPendingRaffles(Instant.now()));

        assertEquals(winnersBefore, winnerIdsOf(raffle.getId()));
    }

    @Test
    void aRaffleWithFewerEntriesThanTicketsStillHasAWinner() throws Exception {
        EventPrize raffle = savePrize("소수응모", 10, 100, true, null, 1);
        enter(raffle, "유일응모자");
        takeOffSale(raffle);

        closing.drawPendingRaffles(Instant.now());

        assertEquals(1, countWon(raffle.getId(), true));
    }

    @Test
    void aRaffleNobodyEnteredIsStillMarkedDrawn() {
        EventPrize raffle = savePrize("무응모", 10, 100, false, null, 1);

        assertEquals(1, closing.drawPendingRaffles(Instant.now()));

        assertNotNull(prizes.findById(raffle.getId()).orElseThrow().getDrawnAt());
        // 두 번째 스윈이 다시 집지 않는다 — 안 그러면 영원히 재시도한다.
        assertEquals(0, closing.drawPendingRaffles(Instant.now()));
    }

    @Test
    void ordinaryPrizesAreNeverDrawn() throws Exception {
        EventPrize ordinary = savePrize("일반상품", 10, 5, true, null, 0);
        enter(ordinary, "일반구매자");
        takeOffSale(ordinary);

        assertEquals(0, closing.drawPendingRaffles(Instant.now()));

        assertEquals(1, countWon(ordinary.getId(), null));
        assertNull(prizes.findById(ordinary.getId()).orElseThrow().getDrawnAt());
    }

    private EventPrize savePrize(String name, int priceCoin, Integer stock, boolean active) {
        return savePrize(name, priceCoin, stock, active, null, 0);
    }

    private EventPrize savePrize(String name, int priceCoin, Integer stock, boolean active, Instant closesAt,
                                 int winnerCount) {
        EventPrize prize = new EventPrize(name, priceCoin, stock, closesAt, winnerCount);
        prize.update(name, priceCoin, stock, active, closesAt, winnerCount);
        return prizes.saveAndFlush(prize);
    }

    /** 한 명이 한 장 응모한다. */
    private void enter(EventPrize prize, String nickname) throws Exception {
        Long entrant = member(nickname);
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(entrant))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1" + RECIPIENT + "}"))
                .andExpect(status().isCreated());
    }

    /** 응모를 받은 뒤 판매를 내린다 — 추첨 대상이 되는 상태다. */
    private void takeOffSale(EventPrize prize) {
        prize.close(Instant.now());
        prizes.saveAndFlush(prize);
    }

    private long countWon(Long prizeId, Boolean won) {
        return purchases.findAllByPrizeIdOrderByIdAsc(prizeId).stream()
                .filter(purchase -> won == null ? purchase.getWon() == null : won.equals(purchase.getWon()))
                .count();
    }

    private List<Long> winnerIdsOf(Long prizeId) {
        return purchases.findAllByPrizeIdOrderByIdAsc(prizeId).stream()
                .filter(purchase -> Boolean.TRUE.equals(purchase.getWon()))
                .map(EventPurchase::getId).toList();
    }

    private Long member(String prefix) {
        return createMemberWithWallet(users, wallets, prefix);
    }

    private String bearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    /**
     * {@code DailyCoinGrantInterceptor} fires on every authenticated request, so a fresh member's
     * balance is not stable at {@code 200} until one request has gone through. Called before
     * capturing a "before" balance so the daily grant lands before the measurement, not during it.
     */
    private void warmUpDailyGrant(String bearer) throws Exception {
        mockMvc.perform(get("/api/v1/event-shop/prizes").header("Authorization", bearer))
                .andExpect(status().isOk());
    }
}
