package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The read-only wallet API end to end (spec 003 contracts/wallet-api.md), including the daily
 * grant firing on a member's first authenticated request of the day (FR-003).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WalletApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private WalletService wallets;
    @Autowired private WalletRepository walletRepository;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private UserRepository users;
    @Autowired private WalletProperties properties;

    @Test
    void aMembersFirstRequestOfTheDayAlreadyIncludesTheDailyGrant() throws Exception {
        Long userId = createMember(users, "API지갑");
        wallets.openWallet(userId);

        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance")
                        .value(properties.initialGrant() + properties.dailyGrant()));
    }

    @Test
    void repeatingTheRequestDoesNotChangeTheBalance() throws Exception {
        Long userId = createMember(users, "API반복");
        wallets.openWallet(userId);
        String bearer = bearerFor(userId);
        int expected = properties.initialGrant() + properties.dailyGrant();

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(expected));
        }
        WalletTestSupport.assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aGuestHasNoWalletAndIsRefused() throws Exception {
        long walletsBefore = walletRepository.count();

        mockMvc.perform(get("/api/v1/wallets/me")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token()))
                .andExpect(status().isForbidden());

        assertEquals(walletsBefore, walletRepository.count(), "게스트에게 지갑이 생기면 안 됩니다.");
    }

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me")).andExpect(status().isUnauthorized());
    }

    /**
     * A member with no wallet row is a broken signup, and says so with its own code.
     *
     * <p>Distinct from the guest case above on purpose: a guest is 403 (wallets are not for
     * guests), while this is 404 (the wallet that should exist does not). The daily-grant
     * interceptor fails first and only logs it — 헌법 3조 keeps that failure from taking over the
     * response — so what the client sees is the `WALLET_NOT_FOUND` the exception itself carries
     * (S15P21A604-388, and -402 which moved that code out of this controller so the booth-lease and
     * catalog-purchase paths stopped answering 500 for the same state).
     */
    @Test
    void aMemberWhoseWalletWasNeverOpenedIsNotFound() throws Exception {
        Long userId = createMember(users, "API무지갑");

        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void transactionsAreReturnedNewestFirstInPages() throws Exception {
        Long userId = createMember(users, "API내역");
        wallets.openWallet(userId);
        String bearer = bearerFor(userId);
        // Take the daily grant first, so the spends below really are the newest entries.
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer)).andExpect(status().isOk());
        for (int index = 0; index < 3; index++) {
            wallets.spend(new CoinSpendCommand(userId, 10, "LEASE_PAYMENT", "BOOTH_LEASE",
                    String.valueOf(index), "LEASE_PAYMENT:HISTORY:" + userId + ":" + index));
        }

        mockMvc.perform(get("/api/v1/wallets/me/transactions").param("size", "2").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content[0].entryType").value("SPEND"))
                .andExpect(jsonPath("$.content[0].amount").value(-10))
                .andExpect(jsonPath("$.totalElements").value(5)) // 초기 + 일일 + 차감 3건
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void theOldestEntryIsTheSignupGrant() throws Exception {
        Long userId = createMember(users, "API가입내역");
        wallets.openWallet(userId);
        String bearer = bearerFor(userId);

        mockMvc.perform(get("/api/v1/wallets/me/transactions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[-1:].reasonType").value("INITIAL_GRANT"))
                .andExpect(jsonPath("$.content[-1:].amount").value(properties.initialGrant()));
    }

    @Test
    void anOutOfRangePageSizeIsRejected() throws Exception {
        Long userId = createMember(users, "API페이지");
        wallets.openWallet(userId);
        String bearer = bearerFor(userId);

        mockMvc.perform(get("/api/v1/wallets/me/transactions").param("size", "0").header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/wallets/me/transactions").param("size", "101").header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/wallets/me/transactions").param("page", "-1").header("Authorization", bearer))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMemberWithNoTransactionsGetsAnEmptyPage() throws Exception {
        Long userId = createMember(users, "API빈내역");
        walletRepository.saveAndFlush(new Wallet(userId)); // 지급 없이 지갑만 만든 예외적 상태
        String bearer = bearerFor(userId);

        assertTrue(walletRepository.findByUserId(userId).isPresent());
        mockMvc.perform(get("/api/v1/wallets/me/transactions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)); // 첫 요청의 일일 지급 1건
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
