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

/**
 * 관리자 강제 임대 반납 (S15P21A604-927).
 *
 * <p>가져가는 것은 <b>자리뿐</b>이다. `강제 비공개` 는 공개 포인터만 해제해서 슬롯이 계속 점유로
 * 보이는데, 운영에 필요한 것은 자리를 실제로 비우는 수단이었다. 확정 사항 두 개를 여기서 못 박는다:
 * 회원의 콘텐츠는 보존되고(FR-010), 코인은 돌아오지 않는다(D06).
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

    /** 자리는 비고, 부스는 남는다 — 이 API 의 전부다. */
    @Test
    void takesTheSeatBackAndLeavesTheContentAlone() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "강제대상");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(member, slotId, 1).lease();
        Long boothId = lease.getBoothId();
        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer(member));
        int balanceBefore = wallets.balanceOf(member);
        Long admin = administrator("회수운영자");

        release(slotId, admin, "신고 접수 — 부적절한 이미지").andExpect(status().isNoContent());

        // 자리: 즉시 비어야 한다. 이것이 안 되면 강제 비공개와 다를 바가 없다.
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
        assertEquals(LeaseStatus.CANCELLED, leases.findById(lease.getId()).orElseThrow().getStatus());
        assertNull(booths.findById(boothId).orElseThrow().getCurrentSlotId());

        // 콘텐츠: 임차인이 직접 반납했을 때와 같아야 한다 (FR-010).
        assertTrue(booths.findById(boothId).isPresent(), "회원 부스는 남는다");
        assertEquals(1, countByBooth("booth_layout_drafts", boothId));
        assertEquals(1, countByBooth("booth_layout_published_versions", boothId));

        // 환불 없음 (D06) — 지갑을 건드리지 않는다.
        assertEquals(balanceBefore, wallets.balanceOf(member));
        // 이 삭제되지 않는 처분의 유일한 흔적.
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_actions
                WHERE actor_user_id = ? AND action = 'BOOTH_LEASE_RELEASE' AND target_type = 'BOOTH'
                  AND target_id = ? AND detail = '신고 접수 — 부적절한 이미지'
                """, Integer.class, admin, boothId));

        // 비운 자리는 곧바로 다른 사람이 쓴다.
        Long other = createMemberWithWallet(users, wallets, "후임임차");
        assertEquals(slotId, leaseService.lease(other, slotId, 1).lease().getSlotId());
    }

    /** 회수한 임차인은 한도가 풀려 다른 자리를 바로 빌릴 수 있다 (D01). */
    @Test
    void theEvictedMemberMayLeaseAgainAtOnce() {
        Long member = createMemberWithWallet(users, wallets, "재임대회원");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(member, slotId, 1).lease().getBoothId();

        leaseService.releaseByAdmin(administrator("재임대운영자"), slotId, "운영상 회수");

        BoothLease again = leaseService.lease(member, freeSlotId(), 1).lease();
        assertEquals(boothId, again.getBoothId(), "부스가 보존되므로 같은 부스로 이어진다");
    }

    @Test
    void onlyAnAdministratorMayAsk() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "권한없음");
        Long slotId = freeSlotId();
        leaseService.lease(member, slotId, 1);

        release(slotId, member, "내 자리 내가 회수")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isPresent(), "거절된 요청은 아무것도 바꾸지 않는다");
    }

    /** 마스터의 부스는 자원을 통해서도 보호된다 — AdminGuard 와 같은 규칙이다. */
    @Test
    void theMastersBoothIsProtected() throws Exception {
        Long master = createMemberWithWallet(users, wallets, "마스터임차");
        Long slotId = freeSlotId();
        leaseService.lease(master, slotId, 1);
        jdbc.update("UPDATE users SET is_master=TRUE WHERE id=?", master);

        release(slotId, administrator("침입운영자"), "마스터 자리 회수")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isPresent());
    }

    @Test
    void refusesAnEmptySeatAndAnUnknownSlot() throws Exception {
        Long admin = administrator("빈자리운영자");

        release(freeSlotId(), admin, "빈 자리 회수")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_LEASE_NOT_FOUND"));
        release(9_999_999L, admin, "없는 자리 회수")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_SLOT_NOT_FOUND"));
    }

    @Test
    void refusesAReleaseWithNoReason() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "사유없음");
        Long slotId = freeSlotId();
        leaseService.lease(member, slotId, 1);

        release(slotId, administrator("사유없음운영자"), "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isPresent());
    }

    /**
     * 관리자 자신의 부스는 예외다 — 그 부스는 반납과 함께 삭제되는 구조이므로(S15P21A604-905)
     * 강제 반납도 같은 결말이다. 회원 경로가 관리자 부스에 새지 않는지를 반대편에서 못 박는다.
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

    private org.springframework.test.web.servlet.ResultActions release(Long slotId, Long actor, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/v1/admin/booth-slots/{slotId}/lease/release", slotId)
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
