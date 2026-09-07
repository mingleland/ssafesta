package com.example.ssafesta.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.WalletService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end contract for the avatar catalog, purchases and server-side equip guard. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AvatarItemOwnershipApiIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String FREE_ASSET = "656603128";
    private static final String PAID_ASSET = "2118850617";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private WalletService wallets;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;

    @BeforeEach
    void clearPurchases() {
        jdbc.update("DELETE FROM user_inventory_items");
    }

    @Test
    void catalogContainsNinetySevenSalesUnitsAndTwelveFreeOwnedItems() throws Exception {
        Long userId = newMemberWithWallet();

        mockMvc.perform(get("/api/v1/catalog/items")
                        .queryParam("type", "AVATAR_PART")
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(97))
                .andExpect(jsonPath("$.items[?(@.owned == true)]", hasSize(12)))
                .andExpect(jsonPath("$.items[?(@.assetKey == '1001')].equipSlot").value("HAT"));
    }

    @Test
    void guestCanBrowseButOnlyFreeItemsAreOwned() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/items")
                        .queryParam("type", "AVATAR_PART")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(97))
                .andExpect(jsonPath("$.items[?(@.owned == true)]", hasSize(12)));
    }

    @Test
    void purchaseMakesTheItemOwnedAndChargesExactlyOnce() throws Exception {
        Long userId = newMemberWithWallet();
        Long itemId = itemId(PAID_ASSET);
        settleDailyGrant(userId);
        int before = wallets.balanceOf(userId);

        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId)
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetKey").value(PAID_ASSET))
                .andExpect(jsonPath("$.owned").value(true));

        assertEquals(before - 100, wallets.balanceOf(userId));
        assertEquals(1, inventoryCount(userId, itemId));

        mockMvc.perform(get("/api/v1/catalog/items")
                        .queryParam("type", "AVATAR_PART")
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.assetKey == '" + PAID_ASSET + "')].owned").value(true));

        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId)
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_ALREADY_OWNED"));
        assertEquals(before - 100, wallets.balanceOf(userId));
        assertEquals(1, inventoryCount(userId, itemId));
    }

    @Test
    void concurrentDuplicatePurchaseChargesAndGrantsOnce() throws Exception {
        Long userId = newMemberWithWallet();
        settleDailyGrant(userId);
        Long itemId = itemId(PAID_ASSET);
        int before = wallets.balanceOf(userId);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> purchaseResult(start, userId, itemId));
            var second = executor.submit(() -> purchaseResult(start, userId, itemId));
            start.countDown();
            Set<String> results = Set.of(first.get(), second.get());
            assertEquals(Set.of("CREATED", "ITEM_ALREADY_OWNED"), results);
        }

        assertEquals(before - 100, wallets.balanceOf(userId));
        assertEquals(1, inventoryCount(userId, itemId));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) = 1 FROM coin_ledger_entries WHERE idempotency_key = ?", Boolean.class,
                InventoryService.purchaseKey(userId, itemId)));
    }

    @Test
    void insufficientBalanceLeavesBothWalletAndInventoryUntouched() throws Exception {
        Long userId = newMemberWithWallet();
        settleDailyGrant(userId);
        int balance = wallets.balanceOf(userId);
        wallets.spend(new CoinSpendCommand(userId, balance, "TEST_DRAIN", "TEST", "1", "TEST:DRAIN:" + userId));
        Long itemId = itemId(PAID_ASSET);

        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId)
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_COIN"));

        assertEquals(0, wallets.balanceOf(userId));
        assertEquals(0, inventoryCount(userId, itemId));
    }

    /**
     * Unity 팔레트에서 빠진 3종은 <b>시드가 이미 판매 중지</b>여야 한다 (V20, GitLab #120).
     *
     * <p>이 셋은 {@code AvatarCatalog.asset} 의 {@code items} 배열에서 참조가 빠졌지만
     * {@code .asset} 파일은 디스크에 남아 있다. 그래서 {@code AvatarCatalogSeedContractTest} 가
     * 잡지 못한다 — 그 테스트는 파일 105개를 세고 {@code AvatarCatalog.asset} 은 제외한다.
     * 판매 여부를 정하는 것은 파일의 존재가 아니라 배열인데 세는 것은 파일 쪽이다.
     *
     * <p>거절 동작 자체는 {@link #stoppedSaleAndGuestPurchaseAreRejected} 가 이미 덮는다. 여기서
     * 고정하는 것은 <b>시드 상태</b>다 — 되돌려지면 코인이 빠지고 입을 수 없는 상품이 돌아온다.
     */
    @Test
    void partsMissingFromTheUnityPaletteAreNotOnSale() {
        for (String assetKey : List.of("1814256283", "356384796", "415781343")) {
            Boolean onSale = jdbc.queryForObject(
                    "SELECT is_on_sale FROM catalog_items WHERE asset_key = ?", Boolean.class, assetKey);
            assertFalse(onSale, assetKey + " 는 Unity 팔레트에 없으므로 판매 중지여야 한다");
        }
    }

    @Test
    void stoppedSaleAndGuestPurchaseAreRejected() throws Exception {
        Long userId = newMemberWithWallet();
        Long itemId = itemId(PAID_ASSET);
        jdbc.update("UPDATE catalog_items SET is_on_sale = FALSE WHERE id = ?", itemId);
        try {
            mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId)
                            .header("Authorization", bearerFor(userId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ITEM_NOT_ON_SALE"));
        } finally {
            jdbc.update("UPDATE catalog_items SET is_on_sale = TRUE WHERE id = ?", itemId);
        }

        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId)
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void unknownCatalogItemIsNotFound() throws Exception {
        Long userId = newMemberWithWallet();
        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", Long.MAX_VALUE)
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_ITEM_NOT_FOUND"));
    }

    /**
     * Same broken state, same answer as the booth-lease path and {@code GET /wallets/me}: a missing
     * wallet is the member's problem, reported as such, not as a server fault (T-113).
     */
    @Test
    void purchaseWithoutAWalletIsRefusedNotAnInternalError() throws Exception {
        Long userId = users.save(new User("소유권무지갑" + SEQUENCE.incrementAndGet())).getId();

        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId(PAID_ASSET))
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void unequippedOrOwnedPartsCanBeSavedButUnownedPartCannot() throws Exception {
        Long userId = newMemberWithWallet();
        String freeOnly = code(FREE_ASSET);
        String paid = code(PAID_ASSET);

        mockMvc.perform(saveAvatar(userId, freeOnly)).andExpect(status().isOk());
        mockMvc.perform(saveAvatar(userId, paid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AVATAR_ITEM_NOT_OWNED"))
                .andExpect(jsonPath("$.errors[0].rule").value("ITEM_NOT_OWNED"))
                .andExpect(jsonPath("$.errors[0].objectId").value(PAID_ASSET));
        assertFalse(paid.equals(storedAvatar(userId)));

        Long itemId = itemId(PAID_ASSET);
        mockMvc.perform(post("/api/v1/catalog/items/{itemId}/purchases", itemId)
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isCreated());
        mockMvc.perform(saveAvatar(userId, paid)).andExpect(status().isOk());
        assertEquals(paid, storedAvatar(userId));
    }

    @Test
    void presetAndUnrecognizedModularShapesRemainBackwardCompatible() throws Exception {
        Long userId = newMemberWithWallet();
        mockMvc.perform(saveAvatar(userId, "sk_01")).andExpect(status().isOk());
        mockMvc.perform(saveAvatar(userId, "fa|g=0|i=1,2,3|p=unchanged")).andExpect(status().isOk());
    }

    private Long newMemberWithWallet() {
        Long userId = users.save(new User("소유권a" + SEQUENCE.incrementAndGet())).getId();
        wallets.openWallet(userId);
        return userId;
    }

    private Long itemId(String assetKey) {
        return jdbc.queryForObject("SELECT id FROM catalog_items WHERE asset_key = ?", Long.class, assetKey);
    }

    private int inventoryCount(Long userId, Long itemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_inventory_items WHERE user_id = ? AND catalog_item_id = ?",
                Integer.class, userId, itemId);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    /** The first authenticated request applies today's independent daily grant. */
    private void settleDailyGrant(Long userId) throws Exception {
        mockMvc.perform(get("/api/v1/catalog/items")
                        .queryParam("type", "AVATAR_PART")
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk());
    }

    private String purchaseResult(CountDownLatch start, Long userId, Long itemId) throws Exception {
        start.await();
        try {
            inventory.purchase(userId, itemId);
            return "CREATED";
        } catch (com.example.ssafesta.common.ApiException exception) {
            return exception.errorCode().name();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder saveAvatar(
            Long userId, String avatarCode) {
        return put("/api/v1/users/me/avatar")
                .header("Authorization", bearerFor(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content(json.writeValueAsString(Map.of("avatarCode", avatarCode)));
    }

    private String storedAvatar(Long userId) {
        return jdbc.queryForObject("SELECT avatar_code FROM users WHERE id = ?", String.class, userId);
    }

    private static String code(String firstSlot) {
        return "fa|g=0|i=" + firstSlot + ",0,0,0,0,0,0,0|p=unchanged";
    }
}
