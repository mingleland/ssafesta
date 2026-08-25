package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reconciliation and administrator adjustment (spec 003 FR-013, FR-014). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CoinReconciliationIntegrationTest {

    @Autowired private WalletService wallets;
    @Autowired private CoinReconciliationService reconciliation;
    @Autowired private CoinReconciliationRunRepository runs;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aHealthyLedgerReportsNoMismatch() {
        Long userId = createMember(users, "정합정상");
        wallets.openWallet(userId);
        wallets.spend(new CoinSpendCommand(userId, 40, "LEASE_PAYMENT", "BOOTH_LEASE", "1",
                "LEASE_PAYMENT:HEALTHY:" + userId));

        CoinReconciliationRun run = reconciliation.run();

        assertEquals(CoinReconciliationRun.Status.COMPLETED, run.getStatus());
        assertEquals(0, run.getMismatchedWalletCount());
        assertTrue(run.isConsistent());
        assertTrue(run.getCheckedWalletCount() > 0);
    }

    @Test
    void aDivergedBalanceIsDetectedAndRecordedButNotCorrected() {
        Long userId = createMember(users, "정합불일치");
        wallets.openWallet(userId);
        Wallet wallet = wallets.requireWallet(userId);
        int correctBalance = wallet.getBalance();
        // Simulate the failure this check exists for: a balance that no longer matches its ledger.
        jdbc.update("UPDATE wallets SET balance = balance + 77 WHERE id = ?", wallet.getId());

        CoinReconciliationRun run = reconciliation.run();

        assertEquals(CoinReconciliationRun.Status.COMPLETED, run.getStatus());
        assertEquals(1, run.getMismatchedWalletCount());
        assertNotNull(run.getMismatches());
        assertTrue(run.getMismatches().contains(String.valueOf(wallet.getId())));

        Integer balanceAfterCheck = jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE id = ?", Integer.class, wallet.getId());
        assertEquals(correctBalance + 77, balanceAfterCheck, "점검은 잔액을 자동 보정하지 않아야 합니다.");
        assertEquals(correctBalance, wallets.ledgerSumOf(wallet.getId()));

        // Leave the database consistent for the rest of the suite.
        jdbc.update("UPDATE wallets SET balance = ? WHERE id = ?", correctBalance, wallet.getId());
    }

    @Test
    void everyRunIsRecorded() {
        long before = runs.count();

        reconciliation.run();

        assertEquals(before + 1, runs.count());
        assertNotNull(runs.findFirstByOrderByStartedAtDescIdDesc().orElseThrow().getFinishedAt());
    }

    @Test
    void administratorAdjustmentsAreRecordedInTheLedgerWithTheActor() {
        Long userId = createMember(users, "관리자조정");
        Long adminId = createMember(users, "관리자");
        wallets.openWallet(userId);
        int before = wallets.balanceOf(userId);

        wallets.adjustByAdmin(new CoinAdminAdjustCommand(userId, 500, "보상 누락 보정", adminId,
                "ADMIN_ADJUSTMENT:" + userId + ":1"));
        wallets.adjustByAdmin(new CoinAdminAdjustCommand(userId, -200, "오지급 회수", adminId,
                "ADMIN_ADJUSTMENT:" + userId + ":2"));

        assertEquals(before + 300, wallets.balanceOf(userId));
        CoinLedgerEntry increase = ledger.findByIdempotencyKey("ADMIN_ADJUSTMENT:" + userId + ":1").orElseThrow();
        assertEquals(LedgerEntryType.CHARGE, increase.getEntryType());
        assertEquals(CoinReason.ADMIN_ADJUSTMENT, increase.getReasonType());
        assertEquals(CoinReason.ADMIN_ACTOR_REFERENCE_TYPE, increase.getReferenceType());
        assertEquals(String.valueOf(adminId), increase.getReferenceId());
        CoinLedgerEntry decrease = ledger.findByIdempotencyKey("ADMIN_ADJUSTMENT:" + userId + ":2").orElseThrow();
        assertEquals(LedgerEntryType.SPEND, decrease.getEntryType());
        WalletTestSupport.assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void anAdministratorCannotDriveABalanceNegative() {
        Long userId = createMember(users, "관리자과다감액");
        Long adminId = createMember(users, "관리자2");
        wallets.openWallet(userId);
        int before = wallets.balanceOf(userId);

        assertThrows(InsufficientCoinException.class, () -> wallets.adjustByAdmin(
                new CoinAdminAdjustCommand(userId, -(before + 1), "과다 회수", adminId,
                        "ADMIN_ADJUSTMENT:" + userId + ":over")));

        assertEquals(before, wallets.balanceOf(userId));
        WalletTestSupport.assertBalanceMatchesLedger(wallets, userId);
    }
}
