package com.example.ssafesta.staff;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 직원 초대 왕복과 거절 경우들 (spec 011 US3, contracts §A).
 *
 * <p>왕복 하나로 끝내지 않는 이유는 이 기능의 값어치가 <b>거절</b>에 있기 때문이다 — 초대는
 * 남의 부스에 사람을 넣는 동작이라, 누가 보낼 수 있고 누가 수락할 수 있는지가 틀리면 성공 경로가
 * 아무리 잘 돌아도 의미가 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StaffInvitationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private StaffInvitationService invitationService;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    // ── 왕복 ────────────────────────────────────────────────────────────────

    /**
     * 초대 → 목록 → 수락 → 권한 행사. 마지막 단계가 이 기능의 목적이다.
     *
     * <p>{@code CONTENT_EDITOR} 로 받은 사람이 수락 <b>전</b>에는 배치를 못 고치고 <b>후</b>에는
     * 고친다 — 초대가 실제로 권한을 옮겼다는 증거는 그 차이뿐이다.
     */
    @Test
    void inviteAcceptAndThenEdit() throws Exception {
        Owner owner = leasedOwner("초대왕복");
        Member invitee = member("받는사람");

        mockMvc.perform(editorProbe(owner.boothId(), invitee))
                .andExpect(status().isForbidden());

        Long invitationId = invite(owner, invitee.nickname(), "CONTENT_EDITOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CONTENT_EDITOR"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString()
                .transform(this::invitationIdOf);

        mockMvc.perform(get("/api/v1/staff-invitations/mine").header("Authorization", invitee.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].invitationId").value(invitationId))
                .andExpect(jsonPath("$[0].boothName").value("초대왕복 부스"));

        mockMvc.perform(post("/api/v1/staff-invitations/{id}/accept", invitationId)
                        .header("Authorization", invitee.bearer()))
                .andExpect(status().isNoContent());

        assertEquals("CONTENT_EDITOR", jdbc.queryForObject(
                "SELECT role FROM booth_staffs WHERE booth_id = ? AND user_id = ?",
                String.class, owner.boothId(), invitee.userId()));
        mockMvc.perform(editorProbe(owner.boothId(), invitee))
                .andExpect(status().isOk());
    }

    /** 상담원은 수락해도 편집은 못 한다 (FR-002, C-09) — 초대가 역할을 그대로 옮긴다는 확인. */
    @Test
    void consultantAcceptsButStillCannotEdit() throws Exception {
        Owner owner = leasedOwner("상담원초대");
        Member invitee = member("상담원");

        acceptInvitation(owner, invitee, "CONSULTANT");

        mockMvc.perform(editorProbe(owner.boothId(), invitee))
                .andExpect(status().isForbidden());
    }

    /** 수락한 `ADMIN` 은 다시 남을 초대할 수 있다 — 권한이 옮겨 갔다는 두 번째 증거. */
    @Test
    void acceptedAdminMayInviteOthers() throws Exception {
        Owner owner = leasedOwner("관리자초대");
        Member admin = member("관리자");
        acceptInvitation(owner, admin, "ADMIN");
        Member third = member("세번째");

        mockMvc.perform(post("/api/v1/booths/{id}/staff-invitations", owner.boothId())
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(third.nickname(), "CONSULTANT")))
                .andExpect(status().isCreated());
    }

    // ── 누가 보낼 수 있는가 ─────────────────────────────────────────────────

    /** `CONTENT_EDITOR` 는 콘텐츠를 고치지만 사람을 들이지는 못한다. */
    @Test
    void contentEditorCannotInvite() throws Exception {
        Owner owner = leasedOwner("편집자권한");
        Member editor = member("편집자");
        acceptInvitation(owner, editor, "CONTENT_EDITOR");
        Member third = member("편집자가부른사람");

        mockMvc.perform(post("/api/v1/booths/{id}/staff-invitations", owner.boothId())
                        .header("Authorization", editor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(third.nickname(), "CONSULTANT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_MANAGER_FORBIDDEN"));
    }

    @Test
    void strangerCannotInvite() throws Exception {
        Owner owner = leasedOwner("남의부스");
        Member stranger = member("외부인");
        Member target = member("표적");

        mockMvc.perform(post("/api/v1/booths/{id}/staff-invitations", owner.boothId())
                        .header("Authorization", stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(target.nickname(), "CONSULTANT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_MANAGER_FORBIDDEN"));
    }

    // ── 거절 경우 ───────────────────────────────────────────────────────────

    @Test
    void unknownNicknameIsNotFound() throws Exception {
        Owner owner = leasedOwner("없는닉네임");

        invite(owner, "존재하지않는닉네임", "CONSULTANT")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAFF_INVITEE_NOT_FOUND"));
    }

    @Test
    void invitingTheOwnerIsRejected() throws Exception {
        Owner owner = leasedOwner("자기자신");

        invite(owner, owner.nickname(), "CONSULTANT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_ALREADY_MEMBER"));
    }

    @Test
    void invitingAnExistingStaffIsRejected() throws Exception {
        Owner owner = leasedOwner("이미직원");
        Member staff = member("기존직원");
        acceptInvitation(owner, staff, "CONSULTANT");

        invite(owner, staff.nickname(), "CONSULTANT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_ALREADY_MEMBER"));
    }

    @Test
    void secondPendingInvitationIsRejected() throws Exception {
        Owner owner = leasedOwner("중복초대");
        Member invitee = member("두번불린사람");
        invite(owner, invitee.nickname(), "CONSULTANT").andExpect(status().isCreated());

        invite(owner, invitee.nickname(), "CONTENT_EDITOR")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_INVITATION_PENDING"));
    }

    @Test
    void roleOutsideTheVocabularyIsRejected() throws Exception {
        Owner owner = leasedOwner("잘못된역할");
        Member invitee = member("대상자");

        invite(owner, invitee.nickname(), "SUPERVISOR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }

    // ── 누가 수락할 수 있는가 ───────────────────────────────────────────────

    @Test
    void someoneElsesInvitationCannotBeAccepted() throws Exception {
        Owner owner = leasedOwner("남의초대");
        Member invitee = member("초대받은사람");
        Member other = member("엉뚱한사람");
        Long invitationId = createdInvitationId(owner, invitee, "CONSULTANT");

        mockMvc.perform(post("/api/v1/staff-invitations/{id}/accept", invitationId)
                        .header("Authorization", other.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_INVITATION_FORBIDDEN"));
    }

    /**
     * 48시간이 지난 초대는 <b>스위퍼를 기다리지 않고</b> 거부된다 (C-07).
     *
     * <p>배치가 옮겨 주기를 기다리면 "48시간" 이 스케줄러 주기만큼 늘어난다. 그래서 행은
     * {@code PENDING} 그대로 두고 기한만 과거로 민다.
     */
    @Test
    void expiredInvitationCannotBeAccepted() throws Exception {
        Owner owner = leasedOwner("만료초대");
        Member invitee = member("늦은사람");
        Long invitationId = createdInvitationId(owner, invitee, "CONSULTANT");
        pushExpiryIntoThePast(invitationId);

        mockMvc.perform(post("/api/v1/staff-invitations/{id}/accept", invitationId)
                        .header("Authorization", invitee.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_INVITATION_NOT_PENDING"));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM staff_invitations WHERE id = ?", String.class, invitationId),
                "거부는 읽는 쪽 판정이다 — 수락 경로가 상태를 바꾸지는 않는다.");
    }

    /** 만료분은 목록에도 없다 — 눌러 봐야 409 인 카드를 보여 주지 않는다 (FR-016). */
    @Test
    void expiredInvitationIsNotListed() throws Exception {
        Owner owner = leasedOwner("만료목록");
        Member invitee = member("목록사람");
        pushExpiryIntoThePast(createdInvitationId(owner, invitee, "CONSULTANT"));

        mockMvc.perform(get("/api/v1/staff-invitations/mine").header("Authorization", invitee.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── 취소 ────────────────────────────────────────────────────────────────

    @Test
    void cancelledInvitationCannotBeAccepted() throws Exception {
        Owner owner = leasedOwner("취소초대");
        Member invitee = member("취소당한사람");
        Long invitationId = createdInvitationId(owner, invitee, "CONSULTANT");

        mockMvc.perform(delete("/api/v1/booths/{boothId}/staff-invitations/{id}",
                        owner.boothId(), invitationId).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/staff-invitations/{id}/accept", invitationId)
                        .header("Authorization", invitee.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_INVITATION_NOT_PENDING"));
    }

    /** 다른 부스의 초대는 이 부스 경로로 취소되지 않는다 — 경로의 boothId 가 장식이 아니다. */
    @Test
    void cancellingThroughAnotherBoothIsNotFound() throws Exception {
        Owner owner = leasedOwner("진짜부스");
        Owner otherOwner = leasedOwner("다른부스");
        Member invitee = member("대상자둘");
        Long invitationId = createdInvitationId(owner, invitee, "CONSULTANT");

        mockMvc.perform(delete("/api/v1/booths/{boothId}/staff-invitations/{id}",
                        otherOwner.boothId(), invitationId)
                        .header("Authorization", otherOwner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAFF_INVITATION_NOT_FOUND"));
    }

    // -- 만료 스위퍼 --------------------------------------------------------

    /**
     * 스위퍼가 기한 지난 대기분을 EXPIRED 로 옮긴다 (C-07).
     *
     * <p>정확성은 읽는 쪽이 이미 지고 있으므로(위 만료 테스트들) 이 패스가 고치는 것은 목록에
     * PENDING 이 영영 쌓이는 쪽이다. 그래서 확인할 것은 "옮겨졌는가" 하나다.
     */
    @Test
    void sweeperMovesOverduePendingToExpired() throws Exception {
        Owner owner = leasedOwner("스위퍼");
        Member invitee = member("스위퍼대상");
        Long invitationId = createdInvitationId(owner, invitee, "CONSULTANT");
        pushExpiryIntoThePast(invitationId);

        invitationService.expireStale();

        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM staff_invitations WHERE id = ?", String.class, invitationId));
    }

    /** 아직 기한이 남은 초대는 건드리지 않는다. */
    @Test
    void sweeperLeavesLiveInvitationsAlone() throws Exception {
        Owner owner = leasedOwner("스위퍼보존");
        Member invitee = member("살아있는초대");
        Long invitationId = createdInvitationId(owner, invitee, "CONSULTANT");

        invitationService.expireStale();

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM staff_invitations WHERE id = ?", String.class, invitationId));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ResultActions invite(Owner owner, String nickname, String role) throws Exception {
        return mockMvc.perform(post("/api/v1/booths/{id}/staff-invitations", owner.boothId())
                .header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(nickname, role)));
    }

    private Long createdInvitationId(Owner owner, Member invitee, String role) throws Exception {
        return invitationIdOf(invite(owner, invitee.nickname(), role)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void acceptInvitation(Owner owner, Member invitee, String role) throws Exception {
        Long invitationId = createdInvitationId(owner, invitee, role);
        mockMvc.perform(post("/api/v1/staff-invitations/{id}/accept", invitationId)
                        .header("Authorization", invitee.bearer()))
                .andExpect(status().isNoContent());
    }

    private void pushExpiryIntoThePast(Long invitationId) {
        jdbc.update("UPDATE staff_invitations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), invitationId);
    }

    private Long invitationIdOf(String responseBody) {
        JsonNode node = jsonMapper.readTree(responseBody);
        return node.get("invitationId").asLong();
    }

    private String inviteBody(String nickname, String role) {
        return "{\"nickname\":\"" + nickname + "\",\"role\":\"" + role + "\"}";
    }

    /**
     * 편집 권한이 있는지 보는 탐침. {@code requireActiveEditor} 를 지나는 경로 중 본문이 가장
     * 단순한 것을 고른 것뿐이고, 검증 대상은 홈페이지 기능이 아니라 <b>게이트</b>다.
     */
    private org.springframework.test.web.servlet.RequestBuilder editorProbe(Long boothId, Member member) {
        return put("/api/v1/booths/{id}/homepage", boothId)
                .header("Authorization", member.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"homepageUrl\":\"https://staff.example.com\"}");
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId, nicknameOf(userId), bearerFor(userId));
    }

    private Member member(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        return new Member(userId, nicknameOf(userId), bearerFor(userId));
    }

    private String nicknameOf(Long userId) {
        return users.findById(userId).orElseThrow().getNickname();
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long boothId, String nickname, String bearer) { }

    private record Member(Long userId, String nickname, String bearer) { }
}
