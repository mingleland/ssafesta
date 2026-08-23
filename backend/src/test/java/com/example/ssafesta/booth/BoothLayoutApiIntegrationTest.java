package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.saveRequest;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** REST contract (spec 005 contracts/layout-api.md §2~§5). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothLayoutApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void anUneditedBoothHasNoDraft() throws Exception {
        Owner owner = leasedOwner("초안없음API");

        mockMvc.perform(get(draftPath(owner.boothId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void savingReturnsTheNewRevision() throws Exception {
        Owner owner = leasedOwner("저장API");

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                // Present even with nothing to report, so the client never branches on its absence.
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.objects[0].objectId").value("screen-1"));
    }

    /** The template catalogue (#19 ④): footprint·상한을 세 파트가 각자 알던 것을 한 곳에서 받는다. */
    @Test
    void theTemplateCatalogueServesFootprintAndCap() throws Exception {
        Owner owner = leasedOwner("템플릿API");

        mockMvc.perform(get("/api/v1/booth-layout-templates")
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(1))
                .andExpect(jsonPath("$.templates[0].template").value("PROJECT_EXHIBITION"))
                .andExpect(jsonPath("$.templates[0].footprint.width").value(6.0))
                .andExpect(jsonPath("$.templates[0].footprint.depth").value(6.0))
                .andExpect(jsonPath("$.templates[0].footprint.height").value(2.72))
                .andExpect(jsonPath("$.templates[0].maxObjects").value(12));
    }

    /** SC-003, the core of it: saving is not publishing. */
    @Test
    void aSavedButUnpublishedBoothServesNoLayout() throws Exception {
        Owner owner = leasedOwner("미공개API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(get(publishedPath(owner.boothId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    @Test
    void aVisitorReadsThePublishedLayoutWithoutAToken() throws Exception {
        Owner owner = leasedOwner("공개API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());

        mockMvc.perform(get(publishedPath(owner.boothId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.template").value("PROJECT_EXHIBITION"))
                .andExpect(jsonPath("$.objects[0].configId").value(152));
    }

    @Test
    void aStaleRevisionConflictsAndSaysWhereTheServerIs() throws Exception {
        Owner owner = leasedOwner("충돌API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAYOUT_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].rule").value("CURRENT_REVISION"));
    }

    @Test
    void someoneElsesBoothIsNotEditable() throws Exception {
        Owner owner = leasedOwner("남의부스");
        Long stranger = createMemberWithWallet(users, wallets, "타인");

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    @Test
    void aGuestCannotEdit() throws Exception {
        Owner owner = leasedOwner("게스트편집");

        mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isForbidden());
    }

    /** The message names the offending key, in Korean, without exposing a class name. */
    @Test
    void anUnknownFieldIsReportedInKorean() throws Exception {
        Owner owner = leasedOwner("미지필드API");

        String body = mockMvc.perform(put(draftPath(owner.boothId()))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                                  {"objectId":"a","type":"DECORATION","scale":2.0,
                                   "position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].rule").value("MALFORMED_LAYOUT"))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("scale"),
                "어떤 필드가 문제인지 알려야 합니다: " + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("com.example"),
                "클래스 경로가 노출되면 안 됩니다: " + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Unrecognized"),
                "Jackson 원문(영문)이 새어 나왔습니다: " + body);
    }

    @Test
    void aMissingBoothIs404() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "없는부스");

        mockMvc.perform(get(draftPath(9_999_999L)).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"));
    }

    @Test
    void anExpiredBoothRefusesItsPublishedLayout() throws Exception {
        Owner owner = leasedOwner("만료API");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(get(publishedPath(owner.boothId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    /** FR-011: the owner keeps working on preserved content even after the lease ends. */
    @Test
    void anExpiredBoothStillOpensItsDraftForTheOwner() throws Exception {
        Owner owner = leasedOwner("만료초안");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(get(draftPath(owner.boothId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk());
    }

    @Test
    void publishingReportsTheVersion() throws Exception {
        Owner owner = leasedOwner("공개응답");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(post(publishPath(owner.boothId())).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.publishedAt").isString())
                .andExpect(jsonPath("$.warnings").isArray());
    }

    // ── 슬롯 기준 published (#62, 계약 §11) — Unity의 앵커는 부스가 아니라 방이다 ──

    @Test
    void aVisitorReadsThePublishedLayoutByRoomWithoutAToken() throws Exception {
        Owner owner = leasedOwner("슬롯공개");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());

        // §5와 같은 body — Unity가 어느 부스를 받았는지 알도록 boothId가 실려 있다.
        mockMvc.perform(get(slotPublishedPath(owner.slotId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(owner.boothId()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.template").value("PROJECT_EXHIBITION"))
                .andExpect(jsonPath("$.objects[0].objectId").value("screen-1"))
                .andExpect(jsonPath("$.objects[0].configId").value(152));
    }

    /** 빈 방과 미공개는 같은 404다 — 둘 다 Unity에게는 "여기 지을 것이 없다"로 같다. */
    @Test
    void anEmptyRoomServesNoLayout() throws Exception {
        Long emptySlotId = jdbc.queryForObject("""
                SELECT id FROM booth_slots
                 WHERE id NOT IN (SELECT slot_id FROM booth_leases WHERE status = 'ACTIVE')
                 ORDER BY id LIMIT 1
                """, Long.class);

        mockMvc.perform(get(slotPublishedPath(emptySlotId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    @Test
    void aRoomWhoseBoothHasNotPublishedServesNoLayout() throws Exception {
        Owner owner = leasedOwner("슬롯미공개");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        mockMvc.perform(get(slotPublishedPath(owner.slotId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    /** 만료는 빈 방으로 뭉개지 않는다 — 방문자는 이유를 들을 자격이 있다 (FR-015). */
    @Test
    void anExpiredRoomAnswersWithTheExpiry() throws Exception {
        Owner owner = leasedOwner("슬롯만료");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        mockMvc.perform(get(slotPublishedPath(owner.slotId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    @Test
    void aMissingRoomIs404() throws Exception {
        mockMvc.perform(get(slotPublishedPath(9_999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_SLOT_NOT_FOUND"));
    }

    /**
     * #62가 존재하는 이유 그 자체: 같은 방을 다시 임대하면 boothId가 바뀐다. 앵커 번호로 §5를
     * 불렀다면 여기서 A의 배치가 그려졌을 것이다 — 오류도 로그도 없이.
     */
    @Test
    void aReleasedRoomServesTheNewTenantsLayout() throws Exception {
        Owner first = leasedOwner("재임대A");
        layouts.saveDraft(first.boothId(), first.userId(), saveRequest(0));
        layouts.publish(first.boothId(), first.userId());
        Long room = first.slotId();

        BoothLayoutTestSupport.releaseLease(jdbc, first.boothId());

        Long secondUserId = createMemberWithWallet(users, wallets, "재임대B");
        Long secondBoothId = booths.save(new Booth(secondUserId, "재임대B 부스")).getId();
        BoothLayoutTestSupport.grantLeaseOnSlot(jdbc, secondBoothId, secondUserId, room);
        layouts.saveDraft(secondBoothId, secondUserId,
                saveRequest(0, """
                        [{"objectId":"panel-b","type":"PROJECT_PANEL",
                          "position":{"x":0.0,"y":0.0,"z":0.0},"rotationY":0.0,"configId":null}]
                        """));
        layouts.publish(secondBoothId, secondUserId);

        mockMvc.perform(get(slotPublishedPath(room)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(secondBoothId))
                .andExpect(jsonPath("$.objects[0].objectId").value("panel-b"));
    }

    /** V12 (T057): slotId 1~12가 Unity 앵커 01~12와 대응한다는 것이 계약이다. */
    @Test
    void theSeedFixesTwelveRoomsToAnchorNumbers() {
        assertEquals(12, jdbc.queryForObject(
                "SELECT count(*) FROM booth_slots WHERE slot_type = 'USER_RENTAL'", Integer.class));

        for (int anchor = 1; anchor <= 12; anchor++) {
            assertEquals("F11-R%02d".formatted(anchor),
                    jdbc.queryForObject("SELECT slot_code FROM booth_slots WHERE id = ?", String.class, anchor),
                    "앵커 " + anchor + "번에 대응하는 슬롯이 어긋났습니다");
        }
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        Long slotId = grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId, slotId);
    }

    private String draftPath(Long boothId) {
        return "/api/v1/booths/" + boothId + "/layouts/draft";
    }

    private String publishPath(Long boothId) {
        return "/api/v1/booths/" + boothId + "/layouts/publish";
    }

    private String publishedPath(Long boothId) {
        return "/api/v1/booths/" + boothId + "/layouts/published";
    }

    private String slotPublishedPath(Long slotId) {
        return "/api/v1/booth-slots/" + slotId + "/layouts/published";
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId, Long slotId) { }
}
