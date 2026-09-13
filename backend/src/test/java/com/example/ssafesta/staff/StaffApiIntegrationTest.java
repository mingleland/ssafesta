package com.example.ssafesta.staff;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothTestSupport;
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
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 부스 직원 목록·역할 변경·제거 (spec 011 US3, FR-002·FR-018).
 *
 * <p>역할 변경이 실제로 <b>권한을 옮기는지</b>를 편집 탐침으로 확인한다. 목록의 문자열만 보면
 * 역할을 바꿔 놓고 게이트가 옛 값을 읽고 있어도 초록이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StaffApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    // ── 목록 ────────────────────────────────────────────────────────────────

    /** Owner 는 저장 행이 아니라 합성 행이다 (FR-018) — 그래서 `readOnly` 로 표시된다. */
    @Test
    void listPutsTheOwnerFirstAsAReadOnlyRow() throws Exception {
        Owner owner = leasedOwner("목록");
        Member staff = joinedStaff(owner, "직원하나", "CONSULTANT");

        mockMvc.perform(get("/api/v1/booths/{id}/staff", owner.boothId())
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].userId").value(owner.userId()))
                .andExpect(jsonPath("$[0].readOnly").value(true))
                .andExpect(jsonPath("$[0].consultationStatus").doesNotExist())
                .andExpect(jsonPath("$[1].userId").value(staff.userId()))
                .andExpect(jsonPath("$[1].role").value("CONSULTANT"))
                .andExpect(jsonPath("$[1].readOnly").value(false))
                .andExpect(jsonPath("$[1].consultationStatus").value("OFFLINE"));
    }

    /** 상담원도 같은 부스에 누가 있는지는 본다 — 편집 게이트를 재사용하지 않은 이유다. */
    @Test
    void consultantMaySeeTheRoster() throws Exception {
        Owner owner = leasedOwner("상담원목록");
        Member consultant = joinedStaff(owner, "상담원", "CONSULTANT");

        mockMvc.perform(get("/api/v1/booths/{id}/staff", owner.boothId())
                        .header("Authorization", consultant.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void strangerCannotSeeTheRoster() throws Exception {
        Owner owner = leasedOwner("남의목록");
        Member stranger = member("외부인");

        mockMvc.perform(get("/api/v1/booths/{id}/staff", owner.boothId())
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_MANAGER_FORBIDDEN"));
    }

    // ── 역할 변경 ───────────────────────────────────────────────────────────

    /** 역할을 올리면 편집 권한이 <b>실제로</b> 따라온다. */
    @Test
    void promotingAConsultantOpensEditing() throws Exception {
        Owner owner = leasedOwner("승격");
        Member staff = joinedStaff(owner, "승격대상", "CONSULTANT");

        mockMvc.perform(editorProbe(owner.boothId(), staff)).andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), staff.userId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CONTENT_EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CONTENT_EDITOR"));

        mockMvc.perform(editorProbe(owner.boothId(), staff)).andExpect(status().isOk());
    }

    /** 내리면 닫힌다 — 반대 방향도 같은 게이트를 지난다. */
    @Test
    void demotingAnEditorClosesEditing() throws Exception {
        Owner owner = leasedOwner("강등");
        Member staff = joinedStaff(owner, "강등대상", "CONTENT_EDITOR");

        mockMvc.perform(editorProbe(owner.boothId(), staff)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), staff.userId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CONSULTANT\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(editorProbe(owner.boothId(), staff)).andExpect(status().isForbidden());
    }

    @Test
    void contentEditorCannotChangeRoles() throws Exception {
        Owner owner = leasedOwner("편집자가역할변경");
        Member editor = joinedStaff(owner, "편집자", "CONTENT_EDITOR");
        Member other = joinedStaff(owner, "다른직원", "CONSULTANT");

        mockMvc.perform(patch("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), other.userId())
                        .header("Authorization", editor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_MANAGER_FORBIDDEN"));
    }

    /** Owner 는 행이 없다 — 404 가 아니라 "바꿀 수 없다" 로 답한다. */
    @Test
    void theOwnerCannotBeRoleChanged() throws Exception {
        Owner owner = leasedOwner("소유자변경");

        mockMvc.perform(patch("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), owner.userId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CONSULTANT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_OWNER_IMMUTABLE"));
    }

    @Test
    void changingSomeoneWhoIsNotStaffIsNotFound() throws Exception {
        Owner owner = leasedOwner("직원아님");
        Member stranger = member("무관한사람");

        mockMvc.perform(patch("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), stranger.userId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CONSULTANT\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
    }

    @Test
    void roleOutsideTheVocabularyIsRejected() throws Exception {
        Owner owner = leasedOwner("어휘밖역할");
        Member staff = joinedStaff(owner, "대상직원", "CONSULTANT");

        mockMvc.perform(patch("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), staff.userId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERVISOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }

    // ── 제거 ────────────────────────────────────────────────────────────────

    @Test
    void removingAStaffClosesEditingAndShrinksTheRoster() throws Exception {
        Owner owner = leasedOwner("제거");
        Member staff = joinedStaff(owner, "제거대상", "CONTENT_EDITOR");

        mockMvc.perform(delete("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), staff.userId())
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(editorProbe(owner.boothId(), staff)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/booths/{id}/staff", owner.boothId())
                        .header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theOwnerCannotBeRemoved() throws Exception {
        Owner owner = leasedOwner("소유자제거");

        mockMvc.perform(delete("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), owner.userId())
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_OWNER_IMMUTABLE"));
    }

    /** 제거된 사람은 다시 초대할 수 있다 — 제거가 영구 차단이 아니라는 확인. */
    @Test
    void aRemovedStaffCanBeInvitedAgain() throws Exception {
        Owner owner = leasedOwner("재초대");
        Member staff = joinedStaff(owner, "재초대대상", "CONSULTANT");
        mockMvc.perform(delete("/api/v1/booths/{boothId}/staff/{userId}", owner.boothId(), staff.userId())
                .header("Authorization", owner.bearer())).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/booths/{id}/staff-invitations", owner.boothId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + staff.nickname() + "\",\"role\":\"CONSULTANT\"}"))
                .andExpect(status().isCreated());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 초대→수락을 거쳐 직원 행을 만든다. 실제 경로로 만들어야 역할 문자열이 계약과 같다. */
    private Member joinedStaff(Owner owner, String prefix, String role) throws Exception {
        Member invitee = member(prefix);
        String body = mockMvc.perform(post("/api/v1/booths/{id}/staff-invitations", owner.boothId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + invitee.nickname() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long invitationId = jsonMapper.readTree(body).get("invitationId").asLong();
        mockMvc.perform(post("/api/v1/staff-invitations/{id}/accept", invitationId)
                        .header("Authorization", invitee.bearer()))
                .andExpect(status().isNoContent());
        return invitee;
    }

    private RequestBuilder editorProbe(Long boothId, Member member) {
        return put("/api/v1/booths/{id}/homepage", boothId)
                .header("Authorization", member.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"homepageUrl\":\"https://staff.example.com\"}");
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId, bearerFor(userId));
    }

    private Member member(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        return new Member(userId, users.findById(userId).orElseThrow().getNickname(), bearerFor(userId));
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId, String bearer) { }

    private record Member(Long userId, String nickname, String bearer) { }
}
