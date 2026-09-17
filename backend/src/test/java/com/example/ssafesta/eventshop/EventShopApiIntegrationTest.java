package com.example.ssafesta.eventshop;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
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

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private EventPrizeRepository prizes;
    @Autowired private EventPurchaseRepository purchases;

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
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":2}"))
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
        String body = "{\"prizeId\":" + prize.getId() + ",\"quantity\":1}";

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
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prize.getId() + ",\"quantity\":2}"))
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
                        .content("{\"prizeId\":" + soldOut.getId() + ",\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_OUT_OF_STOCK"));

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + inactive.getId() + ",\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_INACTIVE"));

        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":999999999,\"quantity\":1}"))
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
                        .content("{\"prizeId\":" + expensive.getId() + ",\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_COIN"));

        assertEquals(5, (int) prizes.findById(expensive.getId()).orElseThrow().getStock());
        assertTrue(purchases.findAll().stream().noneMatch(p -> p.getPrizeId().equals(expensive.getId())));
    }

    private EventPrize savePrize(String name, int priceCoin, Integer stock, boolean active) {
        EventPrize prize = new EventPrize(name, priceCoin, stock);
        prize.update(name, priceCoin, stock, active);
        return prizes.saveAndFlush(prize);
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
