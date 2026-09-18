package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The administrator wallet surface (S15P21A604-806).
 *
 * <p>Two things here are not "expose the existing service" work and carry most of the weight.
 *
 * <p><b>The key names one adjustment, not one administrator.</b> The ledger's reference columns
 * already carry the acting administrator, so reusing that as the idempotency key would fold every
 * later adjustment by the same person into their first. The server scopes the caller's
 * {@code operationId} to the target instead.
 *
 * <p><b>A repeat has to be the same request.</b> Until now the ledger returned the recorded entry
 * for any matching key without comparing what was asked, which is safe only while every key is
 * derived from the row it pays for. This is the first path where a client supplies the key.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminWalletApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private AdminWalletOperationService operations;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aPlainMemberIsRefusedAndAnAdministratorIsNot() throws Exception {
        Long member = newMemberWithWallet("관리자지갑A");
        Long admin = newAdmin("관리자지갑B");

        mockMvc.perform(get("/api/v1/admin/wallets/" + member).header("Authorization", bearerFor(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/wallets/" + member).header("Authorization", bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(member));
    }

    /**
     * One adjustment leaves one ledger row and one audit row.
     *
     * <p>The audit half matters because the ledger alone cannot say who asked: it records the actor
     * as a reference, but not the operation the console issued.
     */
    @Test
    void anAdjustmentWritesOneLedgerRowAndOneAuditRow() throws Exception {
        Long member = newMemberWithWallet("관리자지갑C");
        Long admin = newAdmin("관리자지갑D");
        int before = wallets.balanceOf(member);
        long ledgerBefore = ledgerRows(member);

        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), 100, "보상 누락 보정"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyApplied").value(false))
                .andExpect(jsonPath("$.balanceAfter").value(before + 100));

        assertEquals(ledgerBefore + 1, ledgerRows(member));
        assertEquals(1, auditRows(member));
        assertBalanceMatchesLedger(wallets, member);
    }

    /** The retry of an adjustment is the same adjustment — neither the ledger nor the audit grows. */
    @Test
    void theSameOperationIdWithTheSameRequestChangesNothing() throws Exception {
        Long member = newMemberWithWallet("관리자지갑E");
        Long admin = newAdmin("관리자지갑F");
        String operationId = UUID.randomUUID().toString();

        mockMvc.perform(adjustment(member, admin, operationId, 100, "최초 요청")).andExpect(status().isOk());
        long ledgerAfterFirst = ledgerRows(member);
        int balanceAfterFirst = wallets.balanceOf(member);

        // 사유 문구만 바꿔 다시 보낸다. note 는 멱등 동일성에 넣지 않기로 한 계약이라 충돌이 아니다.
        mockMvc.perform(adjustment(member, admin, operationId, 100, "재시도라 문구가 달라졌다"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyApplied").value(true))
                .andExpect(jsonPath("$.balanceAfter").value(balanceAfterFirst));

        assertEquals(ledgerAfterFirst, ledgerRows(member));
        assertEquals(1, auditRows(member), "재시도는 감사 행도 늘리지 않습니다.");
        assertBalanceMatchesLedger(wallets, member);
    }

    /**
     * A key reused for a different amount is a new request wearing an old name.
     *
     * <p>Before this guard the caller got {@code 200} and the earlier entry's balance, so a console
     * that reused a key would show a movement that never happened.
     */
    @Test
    void theSameOperationIdWithADifferentAmountIsRefused() throws Exception {
        Long member = newMemberWithWallet("관리자지갑G");
        Long admin = newAdmin("관리자지갑H");
        String operationId = UUID.randomUUID().toString();

        mockMvc.perform(adjustment(member, admin, operationId, 100, "최초")).andExpect(status().isOk());
        long ledgerAfterFirst = ledgerRows(member);
        int balanceAfterFirst = wallets.balanceOf(member);

        mockMvc.perform(adjustment(member, admin, operationId, 250, "같은 키에 다른 금액"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                // 거절이지 조회가 아니다 — 앞 요청의 잔액을 응답에 실어 주면 호출자가 반영됐다고 읽는다.
                .andExpect(jsonPath("$.balanceAfter").doesNotExist());

        assertEquals(ledgerAfterFirst, ledgerRows(member));
        assertEquals(1, auditRows(member));
        assertEquals(balanceAfterFirst, wallets.balanceOf(member));
        assertBalanceMatchesLedger(wallets, member);
    }

    /**
     * Two requests carrying one operation id apply once.
     *
     * <p>Driven through the service rather than MockMvc: the race this fixes is the wallet lock and
     * the idempotency lookup, and going through the servlet stack only adds ways for the two
     * threads to miss each other.
     */
    @Test
    void twoConcurrentRequestsWithTheSameOperationIdApplyOnce() throws Exception {
        Long member = newMemberWithWallet("관리자지갑I");
        Long admin = newAdmin("관리자지갑J");
        String operationId = UUID.randomUUID().toString();
        long ledgerBefore = ledgerRows(member);
        int balanceBefore = wallets.balanceOf(member);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> task = () -> {
                start.await();
                try {
                    operations.adjust(member, admin, 100, "동시", operationId);
                    return null;
                } catch (Throwable caught) {
                    return caught;
                }
            };
            List<Future<Throwable>> futures = List.of(pool.submit(task), pool.submit(task));
            start.countDown();
            for (Future<Throwable> future : futures) {
                assertEquals(null, future.get(20, TimeUnit.SECONDS), "동시 요청이 예외로 끝나면 안 됩니다.");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }

        assertEquals(ledgerBefore + 1, ledgerRows(member), "같은 키의 동시 요청은 한 번만 반영됩니다.");
        assertEquals(1, auditRows(member));
        assertEquals(balanceBefore + 100, wallets.balanceOf(member));
        assertBalanceMatchesLedger(wallets, member);
    }

    /** Reading the master is allowed; moving their coins is not. */
    @Test
    void theMasterIsReadableButNotAdjustable() throws Exception {
        Long master = newMemberWithWallet("관리자지갑K");
        jdbc.update("UPDATE users SET account_type = 'ADMIN', is_master = TRUE WHERE id = ?", master);
        Long admin = newAdmin("관리자지갑L");
        try {
            mockMvc.perform(get("/api/v1/admin/wallets/" + master).header("Authorization", bearerFor(admin)))
                    .andExpect(status().isOk());

            mockMvc.perform(adjustment(master, admin, UUID.randomUUID().toString(), -50, "마스터 회수 시도"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));

            assertEquals(0, auditRows(master));
        } finally {
            // 마스터는 전역으로 한 행뿐이라 다른 스위트의 전제를 깨지 않게 되돌린다.
            jdbc.update("UPDATE users SET is_master = FALSE WHERE id = ?", master);
        }
    }

    /** A malformed key is a bad request, not a constraint violation surfacing as a server fault. */
    @Test
    void aMalformedIdempotencyKeyIsAClientError() throws Exception {
        Long member = newMemberWithWallet("관리자지갑M");
        Long admin = newAdmin("관리자지갑N");

        mockMvc.perform(adjustment(member, admin, "키가-아니라-그냥-문자열", 100, "형식 위반"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(adjustment(member, admin, "x".repeat(200), 100, "너무 긴 키"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0, auditRows(member));
    }

    @Test
    void zeroIsRejectedAsARequestError() throws Exception {
        Long member = newMemberWithWallet("관리자지갑O");
        Long admin = newAdmin("관리자지갑P");

        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), 0, "0 조정"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * The most negative int used to reach the balance guard as a positive-looking amount.
     *
     * <p>{@code -Integer.MIN_VALUE} overflows back to itself, so the old {@code canAfford} check
     * passed and the last-resort guard inside {@code Wallet} threw — a 500 for what is really an
     * ordinary refusal.
     */
    @Test
    void theMostNegativeIntIsRefusedAsInsufficientNotAsAServerError() throws Exception {
        Long member = newMemberWithWallet("관리자지갑Q");
        Long admin = newAdmin("관리자지갑R");

        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), Integer.MIN_VALUE, "경계"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_COIN"));

        assertBalanceMatchesLedger(wallets, member);
    }

    /** The other end of the same arithmetic: a credit that would push the balance past the ceiling. */
    @Test
    void aCreditPastTheIntegerCeilingIsRefused() throws Exception {
        Long member = newMemberWithWallet("관리자지갑S");
        Long admin = newAdmin("관리자지갑T");

        // 천장까지 정상 경로로 올린다 — 잔액을 직접 써 넣으면 원장과 어긋나 불변식 I-1 이 깨진다.
        int toCeiling = Integer.MAX_VALUE - wallets.balanceOf(member);
        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), toCeiling, "천장까지"))
                .andExpect(status().isOk());

        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), 1, "한 개 더"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COIN_BALANCE_OVERFLOW"));

        assertEquals(Integer.MAX_VALUE, wallets.balanceOf(member));
        assertBalanceMatchesLedger(wallets, member);
    }

    /** {@code USER_NOT_FOUND} is 401 in this codebase, which would tell an admin to log in again. */
    @Test
    void anUnknownTargetIsNotFound() throws Exception {
        Long admin = newAdmin("관리자지갑U");

        mockMvc.perform(adjustment(999_000_111L, admin, UUID.randomUUID().toString(), 10, "없는 회원"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_TARGET_NOT_FOUND"));
    }

    @Test
    void theLedgerReadsNewestFirstWithPaging() throws Exception {
        Long member = newMemberWithWallet("관리자지갑V");
        Long admin = newAdmin("관리자지갑W");
        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), 10, "첫 조정"))
                .andExpect(status().isOk());
        mockMvc.perform(adjustment(member, admin, UUID.randomUUID().toString(), 20, "둘째 조정"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/wallets/" + member + "/ledger?page=0&size=1")
                        .header("Authorization", bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(20))
                .andExpect(jsonPath("$.size").value(1));

        mockMvc.perform(get("/api/v1/admin/wallets/" + member + "/ledger?size=0")
                        .header("Authorization", bearerFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adjustment(
            Long targetUserId, Long adminUserId, String idempotencyKey, int signedAmount, String note) {
        return post("/api/v1/admin/wallets/" + targetUserId + "/adjustments")
                .header("Authorization", bearerFor(adminUserId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signedAmount\":" + signedAmount + ",\"note\":\"" + note + "\"}");
    }

    private long ledgerRows(Long userId) {
        Long walletId = wallets.requireWallet(userId).getId();
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM coin_ledger_entries WHERE wallet_id = ?", Long.class, walletId);
        return count == null ? 0 : count;
    }

    private long auditRows(Long targetUserId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM admin_actions WHERE action = ? AND target_id = ?",
                Long.class, "COIN_ADJUST", targetUserId);
        return count == null ? 0 : count;
    }

    private Long newMemberWithWallet(String prefix) {
        Long userId = users.save(new User(prefix + UUID.randomUUID().toString().substring(0, 6))).getId();
        wallets.openWallet(userId);
        return userId;
    }

    private Long newAdmin(String prefix) {
        Long userId = users.save(new User(prefix + UUID.randomUUID().toString().substring(0, 6))).getId();
        jdbc.update("UPDATE users SET account_type = 'ADMIN' WHERE id = ?", userId);
        return userId;
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
