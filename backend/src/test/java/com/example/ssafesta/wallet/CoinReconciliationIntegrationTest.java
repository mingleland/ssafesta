package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** Reconciliation and administrator adjustment (spec 003 FR-013, FR-014). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CoinReconciliationIntegrationTest {

    @Autowired private WalletService wallets;
    @Autowired private CoinReconciliationService reconciliation;
    @Autowired private CoinReconciliationRunRepository runs;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CoinReconciliationSweeper sweeper;
    @Autowired private List<ScheduledTaskHolder> scheduledTaskHolders;
    @Value("${app.wallet.reconciliation-scan-interval}") private Duration configuredScanInterval;

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

    // ── 배치 (S15P21A604-693) ────────────────────────────────────────────────
    //
    // run() 은 오랫동안 테스트만 불렀다. 직접 호출 테스트는 @Scheduled 를 지워도 초록이라, 등록
    // 자체를 따로 본다 — 둘 중 하나만 있으면 "돈다" 를 증명하지 못한다.

    @Test
    void theSweeperRunsOneReconciliation() {
        long before = runs.count();

        sweeper.reconcile();

        assertEquals(before + 1, runs.count());
        assertEquals(CoinReconciliationRun.Status.COMPLETED,
                runs.findFirstByOrderByStartedAtDescIdDesc().orElseThrow().getStatus());
    }

    /** {@code @Scheduled} 가 실제로 등록돼 있고, 주기가 설정값을 따른다. 어노테이션을 지우면 여기서 떨어진다. */
    @Test
    void theSweeperIsRegisteredAsAFixedDelayTaskWithTheConfiguredInterval() {
        List<FixedDelayTask> registered = scheduledTaskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.getTask())
                .filter(FixedDelayTask.class::isInstance)
                .map(FixedDelayTask.class::cast)
                // Spring 7 은 runnable 을 Task$OutcomeTrackingRunnable(비공개)로 감싸므로 instanceof 로는
                // 못 본다. 감싼 쪽의 toString 이 ScheduledMethodRunnable 의 "클래스.메서드" 를 그대로 돌려준다.
                .filter(task -> (CoinReconciliationSweeper.class.getName() + ".reconcile")
                        .equals(String.valueOf(task.getRunnable())))
                .toList();

        assertEquals(1, registered.size(), "정합성 점검 스위퍼는 정확히 하나의 fixedDelay 작업으로 등록돼야 한다.");
        assertEquals(configuredScanInterval, registered.getFirst().getIntervalDuration(),
                "주기는 app.wallet.reconciliation-scan-interval 을 따라야 한다 — 상수로 박혀 있으면 설정이 장식이다.");
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
