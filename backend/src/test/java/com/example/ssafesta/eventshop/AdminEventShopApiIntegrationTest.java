package com.example.ssafesta.eventshop;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Prize registration and the purchase queue's fulfillment state machine (S15P21A604-836). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminEventShopApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EventPrizeRepository prizes;
    @Autowired private EventPurchaseRepository purchases;

    @Test
    void nonAdminIsRefusedEveryEventShopAdminRoute() throws Exception {
        Long member = member("일반회원");

        mockMvc.perform(get("/api/v1/admin/event-shop/prizes").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/event-shop/prizes").header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"침입\",\"priceCoin\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPrizeValidatesFieldsAndRegistersOnSuccess() throws Exception {
        Long admin = admin("등록운영자");

        mockMvc.perform(post("/api/v1/admin/event-shop/prizes").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"priceCoin\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/admin/event-shop/prizes").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"텀블러\",\"priceCoin\":300,\"stock\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("텀블러"))
                .andExpect(jsonPath("$.priceCoin").value(300))
                .andExpect(jsonPath("$.stock").value(5))
                .andExpect(jsonPath("$.active").value(true));

        // 이 운영자가 남긴 행만 센다. action 과 target_type 만으로 세면 경품을 등록하는 다른
        // 테스트가 생기는 순간 실행 순서에 따라 값이 갈린다 (S15P21A604-941 과 같은 형태).
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM admin_actions WHERE actor_user_id = ?"
                        + " AND action='PRIZE_CREATE' AND target_type='EVENT_PRIZE'",
                Integer.class, admin));
    }

    @Test
    void updatePrizeCanDeactivateAndMissingPrizeIs404() throws Exception {
        Long admin = admin("수정운영자");
        EventPrize prize = prizes.saveAndFlush(new EventPrize("우산", 200, 3));

        mockMvc.perform(put("/api/v1/admin/event-shop/prizes/" + prize.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"우산\",\"priceCoin\":200,\"stock\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(put("/api/v1/admin/event-shop/prizes/999999999")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"없음\",\"priceCoin\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_PRIZE_NOT_FOUND"));
    }

    @Test
    void purchaseListFiltersByStatusAndShowsPrizeAndBuyerNames() throws Exception {
        Long admin = admin("목록운영자");
        Long buyer = member("구매내역대상");
        EventPrize prize = prizes.saveAndFlush(new EventPrize("목록용품", 20, null));
        buy(buyer, prize.getId());

        mockMvc.perform(get("/api/v1/admin/event-shop/purchases").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.prizeName == '목록용품')]").exists())
                .andExpect(jsonPath("$.content[?(@.buyerNickname)]").exists());

        mockMvc.perform(get("/api/v1/admin/event-shop/purchases").param("status", "FULFILLED")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void fulfillmentFollowsTheStateMachineAndRefusesInvalidTransitions() throws Exception {
        Long admin = admin("처리운영자");
        Long buyer = member("처리대상자");
        EventPrize prize = prizes.saveAndFlush(new EventPrize("처리용품", 20, null));
        Long purchaseId = buy(buyer, prize.getId());

        mockMvc.perform(post("/api/v1/admin/event-shop/purchases/" + purchaseId + "/fulfillment")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillment").value("PENDING"));

        mockMvc.perform(post("/api/v1/admin/event-shop/purchases/" + purchaseId + "/fulfillment")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FULFILLED\",\"note\":\"현장 수령\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillment").value("FULFILLED"));

        // FULFILLED is terminal.
        mockMvc.perform(post("/api/v1/admin/event-shop/purchases/" + purchaseId + "/fulfillment")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PURCHASE_FULFILLMENT_INVALID"));

        mockMvc.perform(post("/api/v1/admin/event-shop/purchases/999999999/fulfillment")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Two successful transitions happened above (PENDING, then FULFILLED); the third call was
        // refused before recording anything.
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM admin_actions WHERE action='PRIZE_FULFILLMENT_UPDATE' AND target_id=?",
                Integer.class, purchaseId));
    }

    @Test
    void cancellingRefundsTheCoinsAndPutsTheStockBack() throws Exception {
        Long admin = admin("취소운영자");
        Long buyer = member("취소대상자");
        EventPrize prize = prizes.saveAndFlush(new EventPrize("취소용품", 20, 3));
        // Read after the purchase, not before: the buyer's first request of the day also lands a
        // daily grant, so a balance captured beforehand is not the one the refund adds to.
        Long purchaseId = buy(buyer, prize.getId());
        int charged = wallets.balanceOf(buyer);
        assertEquals(2, prizes.findById(prize.getId()).orElseThrow().getStock());

        mockMvc.perform(post("/api/v1/admin/event-shop/purchases/" + purchaseId + "/fulfillment")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"note\":\"품절\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillment").value("CANCELLED"));

        assertEquals(charged + 20, wallets.balanceOf(buyer));
        assertEquals(3, prizes.findById(prize.getId()).orElseThrow().getStock());
        // 이 구매자의 지갑으로 좁힌다. 사유와 금액만으로 세면 같은 값을 쓰는 다른 테스트가 남긴
        // 행까지 잡혀서 실행 순서에 따라 통과와 실패가 갈린다 (S15P21A604-941).
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM coin_ledger_entries e JOIN wallets w ON w.id = e.wallet_id"
                        + " WHERE w.user_id = ? AND e.reason_type='PRIZE_REFUND' AND e.amount=20",
                Integer.class, buyer));

        // CANCELLED is terminal, so no second cancel can pay the refund twice.
        mockMvc.perform(post("/api/v1/admin/event-shop/purchases/" + purchaseId + "/fulfillment")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PURCHASE_FULFILLMENT_INVALID"));
        assertEquals(charged + 20, wallets.balanceOf(buyer));
    }

    @Test
    void theListCarriesTheRecipientAndStillRendersPurchasesMadeBeforeItExisted() throws Exception {
        Long admin = admin("수령자운영자");
        Long buyer = member("수령자구매자");
        EventPrize prize = prizes.saveAndFlush(new EventPrize("수령자품목", 20, null));
        Long withRecipient = buy(buyer, prize.getId());
        // GitLab #239 앞의 구매 행은 세 컬럼이 NULL 이다 — V41 이 nullable 로 더한 그 상태를 그대로 만든다.
        jdbc.update("UPDATE event_purchases SET campus=NULL, team_name=NULL, recipient_name=NULL WHERE id=?",
                withRecipient);
        Long legacy = withRecipient;
        Long fresh = buy(buyer, prizes.saveAndFlush(new EventPrize("수령자품목2", 20, null)).getId());

        mockMvc.perform(get("/api/v1/admin/event-shop/purchases").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.purchaseId == " + fresh + " && @.campus == '구미' "
                        + "&& @.teamName == 'A604' && @.recipientName == '황덕')]").exists())
                .andExpect(jsonPath("$.content[?(@.purchaseId == " + legacy + ")]").exists());

        assertTrue(purchases.findById(legacy).orElseThrow().getRecipient().isAbsent(),
                "옛 구매 행은 세 필드가 함께 비어 있어야 합니다.");
    }

    private Long buy(Long buyerUserId, Long prizeId) throws Exception {
        mockMvc.perform(post("/api/v1/event-shop/purchases")
                        .header("Authorization", bearer(buyerUserId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prizeId\":" + prizeId + ",\"quantity\":1,\"campus\":\"구미\","
                                + "\"teamName\":\"A604\",\"recipientName\":\"황덕\"}"))
                .andExpect(status().isCreated());
        return purchases.findAll().stream()
                .filter(p -> p.getPrizeId().equals(prizeId) && p.getBuyerUserId().equals(buyerUserId))
                .findFirst().orElseThrow().getId();
    }

    private Long member(String prefix) {
        return createMemberWithWallet(users, wallets, prefix);
    }

    private Long admin(String prefix) {
        Long userId = member(prefix);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
        return userId;
    }

    private String bearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
