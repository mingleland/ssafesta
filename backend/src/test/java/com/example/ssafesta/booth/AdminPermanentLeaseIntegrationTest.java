package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
     * 관리자도 {@code GET /booths/mine} 으로 자기 부스를 받는다.
     *
     * <p>-905 는 이 응답에서 관리자 부스를 뺀 다음 슬롯 목록의 {@code mine} 으로 찾게 했지만,
     * FE 의 부스 관리창과 스튜디오 게이트는 이 응답만 읽는다 — 그래서 관리자는 임대를 하고도
     * "부스가 없다" 는 화면을 보고 아무것도 할 수 없었다. 응답 모양은 그대로 부스 하나다.
     */
    @Test
    void myBoothAnswersWithTheAdministratorsBoothAndTheSlotListStillMarksItMine() throws Exception {
        Long admin = administrator("목록운영자");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(admin, slotId, 1).lease().getBoothId();

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(boothId.intValue()))
                .andExpect(jsonPath("$.lease.slotId").value(slotId.intValue()));

        // 응답 순서는 findAllOrdered 와 같다. 필터 표현식 대신 자리로 짚어 어느 칸을 보는지 남긴다.
        int index = indexOfSlot(slotId);
        mockMvc.perform(get("/api/v1/booth-slots").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + index + "].slotId").value(slotId.intValue()))
                .andExpect(jsonPath("$[" + index + "].mine").value(true))
                .andExpect(jsonPath("$[" + index + "].boothId").value(boothId.intValue()))
                .andExpect(jsonPath("$[" + index + "].leaseEndsAt").value("2099-12-31T00:00:00Z"));
    }

    /**
     * 관리자 콘솔의 부스 목록은 <b>관리자 부스만</b>, 그리고 <b>전부</b> 돌려준다 (S15P21A604-933).
     *
     * <p>호출한 사람의 것으로 좁히지 않는다 — 부스가 권한을 따라가는데 목록만 사람별로 갈라 있으면
     * 자리를 비운 관리자의 부스는 아무도 못 본다.
     */
    @Test
    void theAdminBoothListShowsEveryAdminBoothAndNoMemberBooth() throws Exception {
        Long admin = administrator("목록주인");
        Long otherAdmin = administrator("다른운영자");
        Long member = createMemberWithWallet(users, wallets, "목록회원");

        Long mine = leaseService.lease(admin, freeSlotId(), 1).lease().getBoothId();
        Long theirs = leaseService.lease(otherAdmin, freeSlotId(), 1).lease().getBoothId();
        Long memberBooth = leaseService.lease(member, freeSlotId(), 1).lease().getBoothId();

        // 전체 개수로 단언하지 않는다. 이 스위트는 클래스 사이에 트랜잭션을 되돌리지 않아서 앞서
        // 돈 클래스가 남긴 관리자 부스도 이 목록에 실린다 — 개수를 박으면 목록 계약이 아니라
        // 실행 순서를 단언하게 된다 (S15P21A604-941 의 횡단 테스트가 들어오면서 드러났다).
        // "관리자 부스만, 그리고 전부" 라는 계약은 아래 세 단언이 그대로 지킨다.
        String body = mockMvc.perform(get("/api/v1/admin/booths").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"boothId\":" + mine));
        assertTrue(body.contains("\"boothId\":" + theirs), "다른 관리자의 부스도 보여야 한다");
        assertFalse(body.contains("\"boothId\":" + memberBooth), "회원 부스는 나오면 안 된다");
    }

    /** 공개 여부는 콘솔이 행동을 가르는 값이라 값이 실제로 움직이는지까지 본다. 설치자 이름도 같이 온다. */
    @Test
    void theAdminBoothListReportsPublicationAndWhoInstalledIt() throws Exception {
        Long admin = administrator("게시운영자");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(admin, slotId, 1).lease().getBoothId();

        mockMvc.perform(get("/api/v1/admin/booths").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].boothId").value(boothId.intValue()))
                .andExpect(jsonPath("$[0].slotId").value(slotId.intValue()))
                .andExpect(jsonPath("$[0].published").value(false))
                .andExpect(jsonPath("$[0].installedBy").value(users.findById(admin).orElseThrow().getNickname()));

        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer(admin));

        mockMvc.perform(get("/api/v1/admin/booths").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].published").value(true));
    }

    /** 관리자 전용 면이다 — 회원은 목록 자체를 보지 못한다. */
    @Test
    void theAdminBoothListIsRefusedToMembers() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "권한없는회원");

        mockMvc.perform(get("/api/v1/admin/booths").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    /** 슬롯을 여러 개 든 관리자에게는 가장 최근에 임대한 부스가 나온다 — 방금 자리를 잡은 그 부스다. */
    @Test
    void myBoothGivesTheMostRecentlyLeasedAdminBooth() throws Exception {
        Long admin = administrator("다중운영자");
        leaseService.lease(admin, freeSlotId(), 1);
        BoothLease latest = leaseService.lease(admin, freeSlotId(), 1).lease();

        mockMvc.perform(get("/api/v1/booths/mine").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(latest.getBoothId().intValue()))
                .andExpect(jsonPath("$.lease.slotId").value(latest.getSlotId().intValue()));
    }

    /** 반납은 슬롯을 지목한다 — 여러 개를 든 관리자에게는 그것만이 어느 부스인지 말한다. */
    @Test
    void administratorReturnsExactlyTheSlotTheyName() {
        Long admin = administrator("반납운영자");
        BoothLease keep = leaseService.lease(admin, freeSlotId(), 1).lease();
        BoothLease drop = leaseService.lease(admin, freeSlotId(), 1).lease();

        leaseService.cancel(admin, drop.getSlotId());

        // 반납한 쪽은 임대 행까지 사라진다 — 부스가 통째로 지워지기 때문이다.
        assertTrue(leases.findById(drop.getId()).isEmpty());
        assertTrue(booths.findById(drop.getBoothId()).isEmpty());
        assertEquals(LeaseStatus.ACTIVE, leases.findById(keep.getId()).orElseThrow().getStatus());
        assertTrue(booths.findById(keep.getBoothId()).isPresent());
    }

    /**
     * 반납한 관리자 부스는 콘텐츠까지 사라진다 (확정 사항).
     *
     * <p>회원 부스와 정반대다 — 회원은 임대가 끝나도 전부 보존된다(FR-010). 관리자는 슬롯마다 새
     * 부스를 받으므로 반납한 것을 남겨 두면 아무도 닿을 수 없는 부스가 쌓인다. <b>방문자가 남긴
     * 행도 함께 지워진다</b> — 부스를 참조하는 15개 외래키 중 cascade 가 하나도 없어서, 그것들을
     * 남기는 삭제는 애초에 실행되지 않는다.
     */
    @Test
    void returningAnAdminBoothDeletesItAndItsContent() throws Exception {
        Long admin = administrator("삭제운영자");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(admin, slotId, 1).lease();
        Long boothId = lease.getBoothId();

        // 지워질 것이 실제로 있게 만든다 — 레이아웃 초안·게시본, 그리고 방문 계측 한 건.
        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer(admin));
        jdbc.update("""
                INSERT INTO booth_visit_events (booth_id, visitor_user_id, world_channel, entered_at)
                VALUES (?, ?, 'main', now())
                """, boothId, admin);
        assertEquals(1, countByBooth("booth_layout_drafts", boothId));
        assertEquals(1, countByBooth("booth_layout_published_versions", boothId));
        assertEquals(1, countByBooth("booth_visit_events", boothId));

        leaseService.cancel(admin, slotId);

        assertTrue(booths.findById(boothId).isEmpty(), "부스 행이 남으면 안 된다");
        assertEquals(0, countByBooth("booth_leases", boothId));
        assertEquals(0, countByBooth("booth_layout_drafts", boothId));
        assertEquals(0, countByBooth("booth_layout_published_versions", boothId));
        assertEquals(0, countByBooth("booth_visit_events", boothId));
        // 슬롯은 곧바로 다시 빌릴 수 있어야 한다.
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
        assertNotEquals(boothId, leaseService.lease(admin, slotId, 1).lease().getBoothId());
    }

    /**
     * 회원 부스는 반대다 — 반납해도 부스와 콘텐츠가 그대로 남는다 (FR-010). 관리자 삭제 경로가
     * 회원 경로로 새지 않는지 못 박는다.
     */
    @Test
    void returningAMemberBoothStillPreservesEverything() throws Exception {
        Long member = createMemberWithWallet(users, wallets, "보존회원");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(member, slotId, 1).lease();
        Long boothId = lease.getBoothId();
        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer(member));

        leaseService.cancel(member, slotId);

        assertTrue(booths.findById(boothId).isPresent());
        assertEquals(LeaseStatus.CANCELLED, leases.findById(lease.getId()).orElseThrow().getStatus());
        assertEquals(1, countByBooth("booth_layout_drafts", boothId));
        assertEquals(1, countByBooth("booth_layout_published_versions", boothId));
        assertEquals(null, booths.findById(boothId).orElseThrow().getCurrentSlotId());
    }

    /**
     * 마스터가 설치한 관리자 부스도 다른 관리자가 조작할 수 있어야 한다 (확정 사항).
     *
     * <p>마스터 보호는 마스터 <b>개인</b>의 부스를 지키는 규칙이다. 관리자 부스는 권한을 따라가므로
     * 설치자가 마스터라는 이유로 막으면 운영 부스가 한 사람에게 묶인다.
     */
    @Test
    void anAdminBoothInstalledByTheMasterIsStillOperableByOtherAdministrators() throws Exception {
        Long master = administrator("마스터운영자");
        jdbc.update("UPDATE users SET is_master=TRUE WHERE id=?", master);
        Long boothId = leaseService.lease(master, freeSlotId(), 1).lease().getBoothId();
        Long otherAdmin = administrator("후임운영자");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", boothId)
                        .header("Authorization", bearer(otherAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://successor.example.com\"}"))
                .andExpect(status().isOk());

        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer(otherAdmin));
        mockMvc.perform(post("/api/v1/admin/booths/{id}/unpublish", boothId)
                        .header("Authorization", bearer(otherAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"운영상 비공개\"}"))
                .andExpect(status().isNoContent());
    }

    /** 마스터 <b>개인</b> 부스는 그대로 보호된다 — 위 예외가 보호 자체를 풀어 버리지 않았는지. */
    @Test
    void theMastersOwnOrdinaryBoothIsStillProtected() throws Exception {
        Long master = createMemberWithWallet(users, wallets, "마스터개인");
        Long boothId = leaseService.lease(master, freeSlotId(), 1).lease().getBoothId();
        jdbc.update("UPDATE users SET account_type='ADMIN', is_master=TRUE WHERE id=?", master);
        Long otherAdmin = administrator("침입운영자");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", boothId)
                        .header("Authorization", bearer(otherAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://intruder.example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));
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

    private int countByBooth(String table, Long boothId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE booth_id = ?", Integer.class, boothId);
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
