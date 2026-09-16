package com.example.ssafesta.minigame;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinAdminAdjustCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.DailyCoinGrantService;
import com.example.ssafesta.wallet.WalletService;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The wire contract the game part reads (GitLab #205 계약, spec 021), at the shipped configuration.
 *
 * <p>The outcome is random, so nothing here asserts a particular tier. What it asserts is what must
 * hold for <b>every</b> draw: the payout is the bet times the tier's multiplier, the reported
 * balance is the wallet's, and the ledger adds up to it. A spin that pays the wrong tier's coins
 * fails on the first loop iteration that hits that tier.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotMachineSpinApiIntegrationTest {

    private static final String MACHINE = "plaza-slot-01";

    @Autowired private MockMvc mockMvc;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private UserRepository users;
    @Autowired private SlotMachineProperties properties;
    @Autowired private DailyCoinGrantService dailyGrants;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void everySpinChargesTheBetAndPaysExactlyItsTier() throws Exception {
        Long userId = member("슬롯판정");
        String bearer = bearerFor(userId);
        int bet = properties.betCoins();
        topUp(userId, 1_000);

        for (int round = 0; round < 40; round++) {
            int before = wallets.balanceOf(userId);
            String body = spin(bearer, MACHINE, bet)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            UUID.fromString(text(body, "sessionId"));
            int tier = number(body, "tier");
            int payout = number(body, "payout");
            assertEquals(bet, number(body, "bet"), "베팅액은 서버 설정값이어야 합니다: " + body);
            assertTrue(tier >= 0 && tier <= properties.tiers().size(), "등급이 표 밖입니다: " + body);
            assertEquals(bet * properties.multiplierOfTier(tier), payout,
                    "지급액이 등급의 배수와 다릅니다: " + body);
            assertEquals(before - bet + payout, number(body, "balanceAfter"),
                    "잔액이 차감·지급과 맞지 않습니다: " + body);
            assertEquals(wallets.balanceOf(userId), number(body, "balanceAfter"),
                    "응답 잔액이 지갑과 다릅니다: " + body);
        }
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void bothSidesOfASpinLandInTheLedgerUnderTheirOwnReasons() throws Exception {
        Long userId = member("슬롯원장");
        String bearer = bearerFor(userId);
        topUp(userId, 1_000);

        int wins = 0;
        for (int round = 0; round < 60; round++) {
            String body = spin(bearer, MACHINE, properties.betCoins())
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String spinId = text(body, "sessionId");
            int payout = number(body, "payout");

            assertEquals(1, ledgerRows(userId, CoinReason.SLOT_BET, spinId),
                    "스핀 한 판에 SLOT_BET 원장이 정확히 하나여야 합니다: " + body);
            // 낙첨은 지급 원장을 남기지 않는다 — 0원짜리 줄은 내역을 읽는 모든 쪽이 건너뛰는 법을
            // 배워야 하는 줄이다.
            assertEquals(payout > 0 ? 1 : 0, ledgerRows(userId, CoinReason.SLOT_PAYOUT, spinId),
                    "지급 원장 수가 payout 과 맞지 않습니다: " + body);
            if (payout > 0) {
                wins++;
            }
        }
        assertTrue(wins > 0, "60판 동안 한 번도 당첨되지 않았습니다 — 확률표가 지급하지 않습니다.");

        // 슬롯은 타이밍 스톱의 일일 50코인 한도를 건드리지 않는다 (#205 확정값 2). 한도는 사유로
        // 집계되므로, 슬롯 지급이 MINIGAME_REWARD 로 기록되는 순간 남의 한도를 먹는다.
        assertEquals(0, wallets.grantedTodayFor(userId, CoinReason.MINIGAME_REWARD),
                "슬롯 지급이 타이밍 스톱 한도에 잡혔습니다.");
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void anUnknownMachineIsRefusedWithoutTouchingTheWallet() throws Exception {
        Long userId = member("슬롯미등록");
        int before = wallets.balanceOf(userId);

        spin(bearerFor(userId), "plaza-slot-99", properties.betCoins())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SLOT_MACHINE_NOT_FOUND"));

        assertEquals(before, wallets.balanceOf(userId), "모르는 기계에 코인이 나갔습니다.");
    }

    @Test
    void aBetThatDisagreesWithTheServerIsRefusedWithoutCharging() throws Exception {
        // 서버가 요청값으로 차감하지 않는다는 것이 이 케이스의 전부다 (헌법 16조). 조용히
        // 설정값으로 고쳐 주면, 100 을 걸었다고 믿는 클라이언트가 10 만 잃고 릴은 다른 판을 그린다.
        Long userId = member("슬롯베팅");
        String bearer = bearerFor(userId);
        int before = wallets.balanceOf(userId);

        spin(bearer, MACHINE, properties.betCoins() + 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("bet"));
        spin(bearer, MACHINE, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bet"));
        mockMvc.perform(post(path(MACHINE)).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bet"));

        assertEquals(before, wallets.balanceOf(userId), "거부된 요청이 코인을 가져갔습니다.");
    }

    @Test
    void anEmptyWalletIsRefusedAndTheBodyCarriesTheBalance() throws Exception {
        // Unity 는 이 값으로 "잔액 N, 필요 10" 을 띄운다. 자기가 들고 있는 잔액은 이 시점에 이미
        // 낡았으므로(다른 기기에서 썼다) 본문의 숫자가 유일하게 맞는 값이다.
        Long userId = member("슬롯부족");
        drainTo(userId, properties.betCoins() - 1);
        int left = wallets.balanceOf(userId);

        spin(bearerFor(userId), MACHINE, properties.betCoins())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_COIN"))
                .andExpect(jsonPath("$.balance").value(left));

        assertEquals(left, wallets.balanceOf(userId), "거부된 스핀이 잔액을 건드렸습니다.");
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void theBalanceFieldIsAbsentFromErrorsThatAreNotAboutCoins() throws Exception {
        // NON_NULL, not null: a client reading "balance" in the body must not get a coin answer
        // from a 404 about a machine.
        String body = spin(bearerFor(member("슬롯봉투")), "plaza-slot-99", properties.betCoins())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertTrue(!body.contains("\"balance\""), "잔액 필드가 코인과 무관한 오류에 실렸습니다: " + body);
    }

    @Test
    void aGuestCannotBet() throws Exception {
        spin("Bearer " + accessTokens.issueGuestToken().token(), MACHINE, properties.betCoins())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(post(path(MACHINE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bet\":10}"))
                .andExpect(status().isUnauthorized());
    }

    private static String path(String machineId) {
        return "/api/v1/minigames/slot-machines/" + machineId + "/spins";
    }

    private ResultActions spin(String bearer, String machineId, Integer bet) throws Exception {
        return mockMvc.perform(post(path(machineId))
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bet == null ? "{}" : "{\"bet\":" + bet + "}"));
    }

    /**
     * A member whose daily grant has already landed.
     *
     * <p>{@code DailyCoinGrantInterceptor} runs on every {@code /api/v1/**} request, so without
     * this the first spin of the day arrives +50 coins richer than the balance read a line earlier
     * — and every "the wallet did not move" assertion here would be off by the grant.
     */
    private Long member(String prefix) {
        Long userId = createMember(users, prefix);
        wallets.openWallet(userId);
        dailyGrants.grantIfDue(userId);
        return userId;
    }

    private void topUp(Long userId, int coins) {
        wallets.adjustByAdmin(new CoinAdminAdjustCommand(userId, coins, "슬롯 테스트 충전", userId,
                "TEST_SLOT_TOPUP:" + userId));
    }

    /** Spends the wallet down to {@code target} so the next spin has to be refused. */
    private void drainTo(Long userId, int target) {
        int spend = wallets.balanceOf(userId) - target;
        wallets.spend(new CoinSpendCommand(userId, spend, CoinReason.ADMIN_ADJUSTMENT,
                CoinReason.ADMIN_ACTOR_REFERENCE_TYPE, String.valueOf(userId),
                "TEST_SLOT_DRAIN:" + userId));
    }

    private int ledgerRows(Long userId, String reason, String spinId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from coin_ledger_entries e join wallets w on w.id = e.wallet_id"
                        + " where w.user_id = ? and e.reason_type = ? and e.reference_id = ?",
                Integer.class, userId, reason, spinId);
        return count == null ? 0 : count;
    }

    private static int number(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(-?[0-9]+)").matcher(body);
        assertTrue(matcher.find(), field + " 가 응답에 없습니다: " + body);
        return Integer.parseInt(matcher.group(1));
    }

    private static String text(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertTrue(matcher.find(), field + " 가 응답에 없습니다: " + body);
        return matcher.group(1);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
