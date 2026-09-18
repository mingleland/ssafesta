package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.List;
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
 * 관리자 부스는 무상·영구 임대다 (S15P21A604-905).
 *
 * <p>세 가지가 일반 회원과 다르고, 셋 다 조용히 깨질 수 있는 것들이라 여기서 못 박는다. 코인을
 * 내지 않고, 만료되지 않으며, 슬롯을 여러 개 동시에 갖는다. 네 번째는 소유가 아니라 <b>권한</b>을
 * 따라간다는 점이다 — 강등된 사람이 부스를 들고 나가면 그때는 되돌릴 방법이 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminPermanentLeaseIntegrationTest {

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private LeaseProperties properties;
    @Autowired private MemberSessionService sessions;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
        jdbc.update("UPDATE users SET is_master=FALSE WHERE is_master=TRUE");
    }

    @Test
    void administratorLeasesFreeAndTheLeaseNeverExpires() {
        Long admin = administrator("무상운영자");
        int before = wallets.balanceOf(admin);

        BoothLeaseService.LeaseOutcome outcome = leaseService.lease(admin, freeSlotId(), 1);

        assertEquals(before, outcome.balanceAfter(), "관리자 임대는 코인을 차감하지 않는다");
        assertEquals(before, wallets.balanceOf(admin));
        assertEquals(0, outcome.lease().getChargedCoin());
        assertTrue(outcome.lease().isPermanent());
        assertEquals(properties.adminEndsAt(), outcome.lease().getEndsAt());
        // 0 Coin 원장 행조차 남기지 않는다 — 지갑 내역에 일어나지 않은 거래가 보이면 안 된다.
        assertEquals(0, countLedger(admin, BoothLeaseService.LEASE_REASON));
    }

    @Test
    void administratorHoldsSeveralSlotsAtOnceEachWithItsOwnBooth() {
        Long admin = administrator("다중운영자");

        BoothLease first = leaseService.lease(admin, freeSlotId(), 1).lease();
        BoothLease second = leaseService.lease(admin, freeSlotId(), 1).lease();

        assertNotEquals(first.getSlotId(), second.getSlotId());
        // 부스가 하나였다면 두 번째 임대가 첫 번째의 current_slot_id 를 빼앗는다.
        assertNotEquals(first.getBoothId(), second.getBoothId());
        assertEquals(first.getSlotId(), booths.findById(first.getBoothId()).orElseThrow().getCurrentSlotId());
        assertEquals(second.getSlotId(), booths.findById(second.getBoothId()).orElseThrow().getCurrentSlotId());
        assertTrue(booths.findById(first.getBoothId()).orElseThrow().isAdminOwned());
    }

    @Test
    void theSweeperLeavesAnAdministratorLeaseAlone() {
        Long admin = administrator("스위퍼운영자");
        BoothLease lease = leaseService.lease(admin, freeSlotId(), 1).lease();

        leaseService.expireStaleLeases();

        BoothLease after = leases.findById(lease.getId()).orElseThrow();
        assertEquals(LeaseStatus.ACTIVE, after.getStatus());
        assertTrue(leases.findValidBySlotId(after.getSlotId(), Instant.now()).isPresent());
    }

    /**
     * 부스가 <b>사람</b>이 아니라 <b>권한</b>을 따라간다는 확정 사항. 강등된 사람은 자기가 만든
     * 부스에서도 밀려나고, 그 자리는 현재 관리자 누구에게나 열려 있다.
     */
    @Test
    void anAdminBoothFollowsTheRoleNotThePersonWhoCreatedIt() throws Exception {
        Long creator = administrator("설치운영자");
        Long boothId = leaseService.lease(creator, freeSlotId(), 1).lease().getBoothId();
        Long otherAdmin = administrator("다른운영자");

        // 다른 관리자는 소유자가 아니어도 들어간다.
        mockMvc.perform(put("/api/v1/booths/{id}/homepage", boothId)
                        .header("Authorization", bearer(otherAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://other-admin.example.com\"}"))
                .andExpect(status().isOk());

        jdbc.update("UPDATE users SET account_type='MEMBER' WHERE id=?", creator);

        // 만든 사람이라도 강등되면 그 다음 요청부터 막힌다 — owner_user_id 는 그대로인데도.
        mockMvc.perform(put("/api/v1/booths/{id}/homepage", boothId)
                        .header("Authorization", bearer(creator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://demoted.example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
        assertEquals(creator, booths.findById(boothId).orElseThrow().getOwnerUserId(),
                "소유자 칸은 누가 설치했는지의 기록으로 남는다 — 권한을 주지 않을 뿐이다");
    }

    /**
     * {@code GET /booths/mine} 은 "내 부스 하나" 계약이다. 관리자 부스를 섞으면 FE·Unity 계약이
     * 같이 바뀌므로 제외한다 — 관리자는 슬롯 목록의 {@code mine} 으로 자기 부스를 찾는다.
     */
    @Test
    void myBoothExcludesAdminBoothsAndTheSlotListStillMarksThemMine() throws Exception {
        Long admin = administrator("목록운영자");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(admin, slotId, 1).lease().getBoothId();

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        // 응답 순서는 findAllOrdered 와 같다. 필터 표현식 대신 자리로 짚어 어느 칸을 보는지 남긴다.
        int index = indexOfSlot(slotId);
        mockMvc.perform(get("/api/v1/booth-slots").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + index + "].slotId").value(slotId.intValue()))
                .andExpect(jsonPath("$[" + index + "].mine").value(true))
                .andExpect(jsonPath("$[" + index + "].boothId").value(boothId.intValue()))
                .andExpect(jsonPath("$[" + index + "].leaseEndsAt").value("2099-12-31T00:00:00Z"));
    }

    /** 반납은 슬롯을 지목한다 — 여러 개를 든 관리자에게는 그것만이 어느 부스인지 말한다. */
    @Test
    void administratorReturnsExactlyTheSlotTheyName() {
        Long admin = administrator("반납운영자");
        BoothLease keep = leaseService.lease(admin, freeSlotId(), 1).lease();
        BoothLease drop = leaseService.lease(admin, freeSlotId(), 1).lease();

        leaseService.cancel(admin, drop.getSlotId());

        assertEquals(LeaseStatus.CANCELLED, leases.findById(drop.getId()).orElseThrow().getStatus());
        assertEquals(LeaseStatus.ACTIVE, leases.findById(keep.getId()).orElseThrow().getStatus());
    }

    /** 일반 회원 경로는 그대로다 — 50 코인이 빠지고 24시간 뒤 끝난다. */
    @Test
    void anOrdinaryMemberStillPaysAndStillExpires() {
        Long member = createMemberWithWallet(users, wallets, "일반임대");
        int before = wallets.balanceOf(member);

        BoothLease lease = leaseService.lease(member, freeSlotId(), 1).lease();

        assertEquals(before - properties.priceCoin(), wallets.balanceOf(member));
        assertEquals(properties.priceCoin(), lease.getChargedCoin());
        assertFalse(lease.isPermanent());
        assertEquals(lease.getStartsAt().plus(properties.duration()), lease.getEndsAt());
        assertFalse(booths.findById(lease.getBoothId()).orElseThrow().isAdminOwned());
    }

    private int indexOfSlot(Long slotId) {
        List<BoothSlot> ordered = slots.findAllOrdered();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(slotId)) {
                return i;
            }
        }
        throw new IllegalStateException("슬롯 목록에 없는 slotId=" + slotId);
    }

    private Long administrator(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
        return userId;
    }

    private int countLedger(Long userId, String reason) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM coin_ledger_entries e
                JOIN wallets w ON w.id = e.wallet_id
                WHERE w.user_id = ? AND e.reason_type = ?
                """, Integer.class, userId, reason);
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
