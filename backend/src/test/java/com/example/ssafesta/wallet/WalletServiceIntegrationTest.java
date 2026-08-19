package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Grants, spends and the balance/ledger invariant (spec 003 User Story 1). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WalletServiceIntegrationTest {

    @Autowired private WalletService wallets;
    @Autowired private DailyCoinGrantService dailyGrants;
    @Autowired private WalletRepository walletRepository;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private WalletProperties properties;

    @Test
    void dailyGrantDayIsMeasuredInKoreanTime() {
        // The configured policy, not just the code path: spec 003 defines the day boundary as KST.
        assertEquals(ZoneId.of("Asia/Seoul"), properties.dailyGrantZone());
        assertEquals(200, properties.initialGrant());
        assertEquals(50, properties.dailyGrant());
    }

    @Test
    void openingAWalletGrantsTheSignupCoinsOnce() {
        Long userId = createMember(users, "가입지급");

        wallets.openWallet(userId);

        assertEquals(properties.initialGrant(), wallets.balanceOf(userId));
        assertEquals(1, ledgerEntriesOf(userId, CoinReason.INITIAL_GRANT));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void reopeningAWalletDoesNotGrantAgain() {
        Long userId = createMember(users, "가입재시도");
        wallets.openWallet(userId);

        wallets.openWallet(userId);
        wallets.openWallet(userId);

        assertEquals(properties.initialGrant(), wallets.balanceOf(userId));
        assertEquals(1, ledgerEntriesOf(userId, CoinReason.INITIAL_GRANT));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void firstAuthenticatedAccessOfTheDayGrantsDailyCoins() {
        Long userId = createMember(users, "일일지급");
        wallets.openWallet(userId);
        LocalDate today = LocalDate.of(2026, 8, 19);

        assertTrue(dailyGrants.grantIfDue(userId, today));

        assertEquals(properties.initialGrant() + properties.dailyGrant(), wallets.balanceOf(userId));
        assertEquals(1, ledgerEntriesOf(userId, CoinReason.DAILY_GRANT));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void repeatedAccessOnTheSameKstDateGrantsNothingMore() {
        Long userId = createMember(users, "일일중복");
        wallets.openWallet(userId);
        LocalDate today = LocalDate.of(2026, 8, 19);
        dailyGrants.grantIfDue(userId, today);
        int afterFirstGrant = wallets.balanceOf(userId);

        // Refresh, re-login, double request — all of them land here again (spec 003 AS1-4).
        for (int attempt = 0; attempt < 5; attempt++) {
            assertFalse(dailyGrants.grantIfDue(userId, today));
        }

        assertEquals(afterFirstGrant, wallets.balanceOf(userId));
        assertEquals(1, ledgerEntriesOf(userId, CoinReason.DAILY_GRANT));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void theNextKstDateGrantsAgain() {
        Long userId = createMember(users, "일일익일");
        wallets.openWallet(userId);
        dailyGrants.grantIfDue(userId, LocalDate.of(2026, 8, 19));

        assertTrue(dailyGrants.grantIfDue(userId, LocalDate.of(2026, 8, 20)));

        assertEquals(properties.initialGrant() + properties.dailyGrant() * 2, wallets.balanceOf(userId));
        assertEquals(2, ledgerEntriesOf(userId, CoinReason.DAILY_GRANT));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void spendingReducesTheBalanceAndRecordsANegativeEntry() {
        Long userId = createMember(users, "차감");
        wallets.openWallet(userId);

        LedgerResult result = wallets.spend(new CoinSpendCommand(userId, 120, "LEASE_PAYMENT",
                "BOOTH_LEASE", "317", "LEASE_PAYMENT:BOOTH_LEASE:317"));

        assertFalse(result.alreadyApplied());
        assertEquals(properties.initialGrant() - 120, result.balanceAfter());
        assertEquals(properties.initialGrant() - 120, wallets.balanceOf(userId));
        CoinLedgerEntry entry = ledger.findByIdempotencyKey("LEASE_PAYMENT:BOOTH_LEASE:317").orElseThrow();
        assertEquals(-120, entry.getAmount());
        assertEquals(LedgerEntryType.SPEND, entry.getEntryType());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void spendingMoreThanTheBalanceIsRefusedAndChangesNothing() {
        Long userId = createMember(users, "잔액부족");
        wallets.openWallet(userId);
        int before = wallets.balanceOf(userId);

        InsufficientCoinException exception = assertThrows(InsufficientCoinException.class,
                () -> wallets.spend(new CoinSpendCommand(userId, before + 1, "LEASE_PAYMENT",
                        "BOOTH_LEASE", "999", "LEASE_PAYMENT:BOOTH_LEASE:999")));

        assertEquals(before + 1, exception.getRequired());
        assertEquals(before, exception.getBalance());
        assertEquals(before, wallets.balanceOf(userId));
        assertTrue(ledger.findByIdempotencyKey("LEASE_PAYMENT:BOOTH_LEASE:999").isEmpty());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aRetriedSpendWithTheSameKeyChangesTheBalanceOnce() {
        Long userId = createMember(users, "멱등차감");
        wallets.openWallet(userId);
        String key = "LEASE_PAYMENT:BOOTH_LEASE:401";

        LedgerResult first = wallets.spend(new CoinSpendCommand(userId, 30, "LEASE_PAYMENT",
                "BOOTH_LEASE", "401", key));
        LedgerResult retry = wallets.spend(new CoinSpendCommand(userId, 30, "LEASE_PAYMENT",
                "BOOTH_LEASE", "401", key));

        assertFalse(first.alreadyApplied());
        assertTrue(retry.alreadyApplied());
        assertEquals(first.entryId(), retry.entryId());
        assertEquals(first.balanceAfter(), retry.balanceAfter());
        assertEquals(properties.initialGrant() - 30, wallets.balanceOf(userId));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void rewardsAndRefundsAreRecordedAsPositiveEntries() {
        Long userId = createMember(users, "보상환불");
        wallets.openWallet(userId);

        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, 15, "MINIGAME_REWARD",
                "MINIGAME_SESSION", "7", "MINIGAME_REWARD:MINIGAME_SESSION:7"));
        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REFUND, 100, "LEASE_REFUND",
                "BOOTH_LEASE", "317", "LEASE_REFUND:BOOTH_LEASE:317"));

        assertEquals(properties.initialGrant() + 115, wallets.balanceOf(userId));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aSpendTypeCannotBeUsedAsAGrant() {
        Long userId = createMember(users, "유형검증");
        wallets.openWallet(userId);

        assertThrows(IllegalArgumentException.class, () -> new CoinCreditCommand(userId,
                LedgerEntryType.SPEND, 10, "MINIGAME_REWARD", null, null, "bad-type-key"));
    }

    @Test
    void anOverlongIdempotencyKeyFailsInsteadOfBeingTruncated() {
        Long userId = createMember(users, "키길이");
        wallets.openWallet(userId);
        String tooLong = "K".repeat(101);

        assertThrows(IllegalArgumentException.class, () -> wallets.spend(
                new CoinSpendCommand(userId, 10, "LEASE_PAYMENT", null, null, tooLong)));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aMemberWithoutAWalletIsReportedNotTreatedAsZero() {
        Long userId = createMember(users, "지갑없음");

        assertThrows(WalletNotFoundException.class, () -> wallets.balanceOf(userId));
        assertTrue(walletRepository.findByUserId(userId).isEmpty());
    }

    private long ledgerEntriesOf(Long userId, String reasonType) {
        Wallet wallet = wallets.requireWallet(userId);
        return ledger.findAll().stream()
                .filter(entry -> entry.getWalletId().equals(wallet.getId()))
                .filter(entry -> entry.getReasonType().equals(reasonType))
                .count();
    }
}
