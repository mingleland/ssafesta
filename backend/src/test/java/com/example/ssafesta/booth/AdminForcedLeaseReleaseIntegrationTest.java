package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
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
 * 강제 비공개는 자리까지 회수한다 (S15P21A604-927).
 *
 * <p>공개 포인터만 내리던 동작이 제보의 원인이었다 — 임대가 살아 있어 슬롯 목록이 계속 점유로
 * 보이고, 그 목록을 읽는 관리자 화면에서는 아무 일도 안 일어난 것처럼 보였다. <b>엔드포인트는
 * 그대로다</b>: 콘솔이 이미 부르는 `POST /admin/booths/{boothId}/unpublish` 가 두 일을 다 한다.
 *
 * <p>확정 사항 둘을 여기서 못 박는다: 회원의 콘텐츠는 보존되고(FR-010), 코인은 돌아오지 않는다(D06).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminForcedLeaseReleaseIntegrationTest {

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
        jdbc.update("UPDATE users SET is_master=FALSE WHERE is_master=TRUE");
    }

    /** 자리는 비고, 부스는 남는다 — 이 조치의 전부다. */
    @Test
    void takesTheSeatBackAndLeavesTheContentAlone() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "강제대상");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(member, slotId, 1).lease();
        Long boothId = lease.getBoothId();
        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer(member));
        int balanceBefore = wallets.balanceOf(member);
        Long admin = administrator("회수운영자");

        unpublish(boothId, admin, "신고 접수 — 부적절한 이미지").andExpect(status().isNoContent());

        // 자리: 즉시 비어야 한다. 이것이 안 되면 제보 상태로 되돌아간다.
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
        assertEquals(LeaseStatus.CANCELLED, leases.findById(lease.getId()).orElseThrow().getStatus());
        assertNull(booths.findById(boothId).orElseThrow().getCurrentSlotId());
        // 비공개도 함께 성립한다 — detachSlot 이 공개 포인터를 비운다.
        assertNull(booths.findById(boothId).orElseThrow().getPublishedLayoutVersion());

        // 콘텐츠: 임차인이 직접 반납했을 때와 같아야 한다 (FR-010).
        assertTrue(booths.findById(boothId).isPresent(), "회원 부스는 남는다");
        assertEquals(1, countByBooth("booth_layout_drafts", boothId));
        assertEquals(1, countByBooth("booth_layout_published_versions", boothId));

        // 환불 없음 (D06) — 지갑을 건드리지 않는다.
        assertEquals(balanceBefore, wallets.balanceOf(member));
        // 감사 두 줄: 무엇을 내렸는지와 누구의 자리를 가져갔는지.
        assertEquals(1, countAction(admin, "BOOTH_UNPUBLISH", boothId));
        assertEquals(1, countAction(admin, "BOOTH_LEASE_RELEASE", boothId));

        // 비운 자리는 곧바로 다른 사람이 쓴다.
        Long other = createMemberWithWallet(users, wallets, "후임임차");
        assertEquals(slotId, leaseService.lease(other, slotId, 1).lease().getSlotId());
    }

    /** 게시본이 없는 부스도 자리는 회수된다 — 비공개 여부와 회수는 별개의 결과다. */
    @Test
    void takesTheSeatBackFromAnUnpublishedBooth() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "미게시대상");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(member, slotId, 1).lease().getBoothId();

        unpublish(boothId, administrator("미게시운영자"), "게시 전 회수").andExpect(status().isNoContent());

        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
        assertTrue(booths.findById(boothId).isPresent());
    }

    /** 회수당한 임차인은 한도가 풀려 다른 자리를 바로 빌리고, 같은 부스를 이어 쓴다 (D01·D08). */
    @Test
    void theEvictedMemberMayLeaseAgainAtOnce() {
        Long member = createMemberWithWallet(users, wallets, "재임대회원");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(member, slotId, 1).lease().getBoothId();

        leaseService.releaseByAdmin(administrator("재임대운영자"), slotId, "운영상 회수");

        assertEquals(boothId, leaseService.lease(member, freeSlotId(), 1).lease().getBoothId());
    }

    @Test
    void onlyAnAdministratorMayAsk() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "권한없음");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(member, slotId, 1).lease().getBoothId();

        unpublish(boothId, member, "내 자리 내가 회수")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isPresent(), "거절된 요청은 아무것도 바꾸지 않는다");
    }

    /** 마스터 <b>개인</b> 부스는 자리도 못 가져간다 — 보호가 자원을 통해서도 성립한다. */
    @Test
    void theMastersOwnBoothIsProtected() throws Exception {
        Long master = createMemberWithWallet(users, wallets, "마스터임차");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(master, slotId, 1).lease().getBoothId();
        jdbc.update("UPDATE users SET is_master=TRUE WHERE id=?", master);

        unpublish(boothId, administrator("침입운영자"), "마스터 자리 회수")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isPresent());
    }

    /**
     * 마스터가 설치한 <b>관리자</b> 부스는 보호 대상이 아니다 — 그 부스는 사람이 아니라 권한을
     * 따라간다(S15P21A604-905). 막으면 운영 부스가 한 사람에게 묶인다.
     */
    @Test
    void anAdminBoothInstalledByTheMasterIsStillReclaimable() throws Exception {
        Long master = administrator("마스터설치");
        jdbc.update("UPDATE users SET is_master=TRUE WHERE id=?", master);
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(master, slotId, 1).lease().getBoothId();

        unpublish(boothId, administrator("후임운영자"), "운영 종료").andExpect(status().isNoContent());

        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
    }

    @Test
    void refusesAReleaseWithNoReason() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "사유없음");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(member, slotId, 1).lease().getBoothId();

        unpublish(boothId, administrator("사유없음운영자"), "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isPresent());
    }

    /** 임대가 없는 부스는 되돌릴 자리가 없다 — 기존 no-op 계약 그대로다. */
    @Test
    void anUnleasedBoothIsANoOp() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "임대없음");
        Long boothId = booths.save(new Booth(member, "임대 없는 부스")).getId();

        unpublish(boothId, administrator("무임대운영자"), "임대 없음").andExpect(status().isNoContent());

        assertNull(booths.findById(boothId).orElseThrow().getCurrentSlotId());
    }

    /**
     * 관리자 자신의 부스는 예외다 — 그 부스는 반납과 함께 삭제되는 구조이므로(S15P21A604-905)
     * 회수도 같은 결말이다. 회원 경로가 관리자 부스로 새지 않는지를 반대편에서 못 박는다.
     */
    @Test
    void anAdminOwnedBoothIsStillDeletedByItsRelease() {
        Long owner = administrator("설치운영자");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(owner, slotId, 1).lease().getBoothId();

        leaseService.releaseByAdmin(administrator("회수동료"), slotId, "운영 종료");

        assertTrue(booths.findById(boothId).isEmpty(), "관리자 부스는 반납과 함께 사라진다");
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
    }

    private ResultActions unpublish(Long boothId, Long actor, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/booths/{boothId}/unpublish", boothId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
    }

    private Long administrator(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
        return userId;
    }

    private int countByBooth(String table, Long boothId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE booth_id = ?", Integer.class, boothId);
    }

    private int countAction(Long actorUserId, String action, Long boothId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_actions
                WHERE actor_user_id = ? AND action = ? AND target_type = 'BOOTH' AND target_id = ?
                """, Integer.class, actorUserId, action, boothId);
    }

    private String bearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private Long freeSlotId() {
        Instant now = Instant.now();
        return slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), now).isEmpty())
                .map(BoothSlot::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("빈 USER_RENTAL 슬롯이 없습니다."));
    }
}
