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

        String key = "LEASE_PAYMENT:BOOTH_LEASE:" + leaseReference(userId);

        LedgerResult result = wallets.spend(new CoinSpendCommand(userId, 120, "LEASE_PAYMENT",
                "BOOTH_LEASE", leaseReference(userId), key));

        assertFalse(result.alreadyApplied());
        assertEquals(properties.initialGrant() - 120, result.balanceAfter());
        assertEquals(properties.initialGrant() - 120, wallets.balanceOf(userId));
        CoinLedgerEntry entry = ledger.findByIdempotencyKey(key).orElseThrow();
        assertEquals(-120, entry.getAmount());
        assertEquals(LedgerEntryType.SPEND, entry.getEntryType());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void spendingMoreThanTheBalanceIsRefusedAndChangesNothing() {
        Long userId = createMember(users, "잔액부족");
        wallets.openWallet(userId);
        int before = wallets.balanceOf(userId);
        String key = "LEASE_PAYMENT:BOOTH_LEASE:" + leaseReference(userId);

        InsufficientCoinException exception = assertThrows(InsufficientCoinException.class,
                () -> wallets.spend(new CoinSpendCommand(userId, before + 1, "LEASE_PAYMENT",
                        "BOOTH_LEASE", leaseReference(userId), key)));

        assertEquals(before + 1, exception.getRequired());
        assertEquals(before, exception.getBalance());
        assertEquals(before, wallets.balanceOf(userId));
        assertTrue(ledger.findByIdempotencyKey(key).isEmpty());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aRetriedSpendWithTheSameKeyChangesTheBalanceOnce() {
        Long userId = createMember(users, "멱등차감");
        wallets.openWallet(userId);
        String key = "LEASE_PAYMENT:BOOTH_LEASE:" + leaseReference(userId);

        LedgerResult first = wallets.spend(new CoinSpendCommand(userId, 30, "LEASE_PAYMENT",
                "BOOTH_LEASE", leaseReference(userId), key));
        LedgerResult retry = wallets.spend(new CoinSpendCommand(userId, 30, "LEASE_PAYMENT",
                "BOOTH_LEASE", leaseReference(userId), key));

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
                "MINIGAME_SESSION", leaseReference(userId),
                "MINIGAME_REWARD:MINIGAME_SESSION:" + leaseReference(userId)));
        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REFUND, 100, "LEASE_REFUND",
                "BOOTH_LEASE", leaseReference(userId),
                "LEASE_REFUND:BOOTH_LEASE:" + leaseReference(userId)));

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

    /**
     * 같은 키가 <b>다른 지갑</b>의 항목을 가리키면 조용히 {@code alreadyApplied} 가 아니라 크게 던진다
     * (S15P21A604-695).
     *
     * <p>키는 전역 UNIQUE 라 조회도 전역이다 — 그래서 주인 검사가 따라와야 한다. 검사가 없으면
     * 호출자는 자기 지갑에 아무 일도 없이 "반영됐다" 를 받는다. 서버가 만든 키가 지갑 사이에서
     * 겹쳤다는 뜻이라 클라이언트 오류(409)가 아니라 서버 결함(500)으로 드러나야 한다.
     * {@code UNIQUE} 위반의 {@code DataIntegrityViolationException} 과는 타입으로 갈린다.
     */
    @Test
    void aKeyAlreadyUsedByAnotherWalletIsRefusedLoudly() {
        Long first = createMember(users, "교차키갑");
        Long second = createMember(users, "교차키을");
        wallets.openWallet(first);
        wallets.openWallet(second);
        String sharedKey = "SURVEY_REWARD:" + first + ":shared";
        wallets.credit(new CoinCreditCommand(first, LedgerEntryType.REWARD, 5, "SURVEY_REWARD",
                "SURVEY", "1", sharedKey));

        assertThrows(IllegalStateException.class, () -> wallets.credit(new CoinCreditCommand(
                second, LedgerEntryType.REWARD, 5, "SURVEY_REWARD", "SURVEY", "1", sharedKey)));

        assertEquals(properties.initialGrant(), wallets.balanceOf(second), "다른 지갑의 키로는 아무 것도 변하지 않는다.");
        Long secondWalletId = walletRepository.findByUserId(second).orElseThrow().getId();
        assertTrue(ledger.findByIdempotencyKey(sharedKey)
                .filter(entry -> entry.getWalletId().equals(secondWalletId)).isEmpty(),
                "을의 원장에 갑의 키가 붙으면 안 된다.");
        assertBalanceMatchesLedger(wallets, second);
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

    /**
     * A business reference that cannot collide with a real one.
     *
     * <p>{@code idempotency_key} is globally UNIQUE and the test database is shared across classes,
     * so a hardcoded key such as {@code LEASE_PAYMENT:BOOTH_LEASE:317} breaks as soon as some other
     * test creates lease #317 — which is exactly what the 004 stress test did (T-111). The
     * non-numeric prefix keeps these out of the range real references can ever take.
     */
    private static String leaseReference(Long userId) {
        return "T" + userId;
    }

    private long ledgerEntriesOf(Long userId, String reasonType) {
        Wallet wallet = wallets.requireWallet(userId);
        return ledger.findAll().stream()
                .filter(entry -> entry.getWalletId().equals(wallet.getId()))
                .filter(entry -> entry.getReasonType().equals(reasonType))
                .count();
    }
}
