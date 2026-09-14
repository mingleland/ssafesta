package com.example.ssafesta.consultation;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상담 요청·수락·종료 왕복과 거절 경우들 (spec 011 US1 P1, contracts §B).
 *
 * <p>P1 은 요청·수락·종료까지다 — 실시간 메시지 송수신은 P2 이고(C-12), 이 단계의 정본은 REST 다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsultationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ConsultationService consultationService;
    @Autowired private com.example.ssafesta.auth.AccessTokenService accessTokens;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    // ── 왕복 ────────────────────────────────────────────────────────────────

    /** 요청 → 대기열 → 수락 → 종료. 이 네 걸음이 US1 P1 의 전부다. */
    @Test
    void requestQueueAcceptAndEnd() throws Exception {
        Fixture booth = leasedBoothWithStaff("상담왕복");
        Member visitor = member("방문자");

        Long requestId = requestIdOf(request(visitor, booth.boothId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresInSeconds").value(600)));

        mockMvc.perform(get("/api/v1/booths/{id}/consultation/requests", booth.boothId())
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value(String.valueOf(requestId)))
                .andExpect(jsonPath("$[0].visitorNickname").value(visitor.nickname()))
                .andExpect(jsonPath("$[0].handoffSummary").doesNotExist());

        mockMvc.perform(post("/api/v1/consultation/requests/{id}/accept", requestId)
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(String.valueOf(requestId)))
                .andExpect(jsonPath("$.sessionId").value(String.valueOf(requestId)))
                .andExpect(jsonPath("$.visitorNickname").value(visitor.nickname()));

        mockMvc.perform(get("/api/v1/booths/{id}/consultation/requests", booth.boothId())
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/v1/consultation/sessions/{id}/end", requestId)
                        .header("Authorization", visitor.bearer()))
                .andExpect(status().isNoContent());
        assertEquals("ENDED", statusOf(requestId));
    }

    /**
     * 요약은 {@code null} 이어도 모든 경로가 돈다 (research R-02).
     *
     * <p>{@code S15P21A604-139} 가 오기 전의 상태를 그대로 고정한다 — 요약이 없다고 상담이
     * 막히면 AI 장애가 사람 상담을 멈추게 된다(헌법 3조).
     */
    @Test
    void everythingWorksWhileTheSummaryIsStillNull() throws Exception {
        Fixture booth = leasedBoothWithStaff("요약없음");
        Member visitor = member("요약없는방문자");

        Long requestId = requestIdOf(request(visitor, booth.boothId(), "conv_01JABC")
                .andExpect(status().isCreated()));

        mockMvc.perform(post("/api/v1/consultation/requests/{id}/accept", requestId)
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoffSummary").doesNotExist());
    }

    /** 방문자가 스스로 거둔다. 취소는 만료와 다른 상태로 남는다. */
    @Test
    void aVisitorCancelsTheirOwnRequest() throws Exception {
        Fixture booth = leasedBoothWithStaff("취소");
        Member visitor = member("취소하는방문자");
        Long requestId = requestIdOf(request(visitor, booth.boothId()).andExpect(status().isCreated()));

        mockMvc.perform(delete("/api/v1/consultation/requests/{id}", requestId)
                        .header("Authorization", visitor.bearer()))
                .andExpect(status().isNoContent());

        assertEquals("CANCELLED", statusOf(requestId));
        mockMvc.perform(post("/api/v1/consultation/requests/{id}/accept", requestId)
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSULTATION_NOT_REQUESTED"));
    }

    // ── 누가 무엇을 할 수 있는가 ────────────────────────────────────────────

    /** 게스트는 청할 수 없다 (FR-014, 헌법 12조). */
    @Test
    void aGuestCannotRequest() throws Exception {
        Fixture booth = leasedBoothWithStaff("게스트");

        mockMvc.perform(post("/api/v1/consultation/requests")
                        .header("Authorization", guestBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boothId\":" + booth.boothId() + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    /** 그 부스 구성원이 아니면 대기열도 수락도 없다. */
    @Test
    void anOutsiderSeesNeitherTheQueueNorCanAccept() throws Exception {
        Fixture booth = leasedBoothWithStaff("외부인");
        Member visitor = member("방문자둘");
        Member outsider = member("남");
        Long requestId = requestIdOf(request(visitor, booth.boothId()).andExpect(status().isCreated()));

        mockMvc.perform(get("/api/v1/booths/{id}/consultation/requests", booth.boothId())
                        .header("Authorization", outsider.bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/consultation/requests/{id}/accept", requestId)
                        .header("Authorization", outsider.bearer()))
                .andExpect(status().isForbidden());
    }

    /** 남의 요청은 취소할 수 없다. */
    @Test
    void anotherMemberCannotCancelMyRequest() throws Exception {
        Fixture booth = leasedBoothWithStaff("남의취소");
        Member visitor = member("주인");
        Member other = member("엉뚱한사람");
        Long requestId = requestIdOf(request(visitor, booth.boothId()).andExpect(status().isCreated()));

        mockMvc.perform(delete("/api/v1/consultation/requests/{id}", requestId)
                        .header("Authorization", other.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSULTATION_FORBIDDEN"));
    }

    /** 상담 당사자가 아니면 끝낼 수 없다. */
    @Test
    void anOutsiderCannotEndTheSession() throws Exception {
        Fixture booth = leasedBoothWithStaff("남의종료");
        Member visitor = member("방문자셋");
        Member outsider = member("무관한사람");
        Long requestId = requestIdOf(request(visitor, booth.boothId()).andExpect(status().isCreated()));
        mockMvc.perform(post("/api/v1/consultation/requests/{id}/accept", requestId)
                        .header("Authorization", booth.staff().bearer())).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/consultation/sessions/{id}/end", requestId)
                        .header("Authorization", outsider.bearer()))
                .andExpect(status().isForbidden());
    }

    // ── 거절 경우 ───────────────────────────────────────────────────────────

    @Test
    void aSecondPendingRequestToTheSameBoothIsRejected() throws Exception {
        Fixture booth = leasedBoothWithStaff("중복요청");
        Member visitor = member("두번청하는사람");
        request(visitor, booth.boothId()).andExpect(status().isCreated());

        request(visitor, booth.boothId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSULTATION_REQUEST_PENDING"));
    }

    /**
     * 없는 부스는 404 다 — 임대 검사만 하던 때는 {@code 409 BOOTH_LEASE_EXPIRED} 로 답해, 클라이언트가
     * 있지도 않은 부스의 임대가 끝났다고 읽었다 (S15P21A604-693).
     */
    @Test
    void anUnknownBoothIsNotFoundRatherThanExpired() throws Exception {
        Member visitor = member("없는부스방문");

        request(visitor, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"));
    }

    @Test
    void anExpiredBoothRefusesRequests() throws Exception {
        Fixture booth = leasedBoothWithStaff("만료부스");
        Member visitor = member("늦은방문자");
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE booth_id = ?", booth.boothId());

        request(visitor, booth.boothId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    /**
     * 10분이 지난 요청은 <b>스위퍼를 기다리지 않고</b> 거부된다 (C-01).
     *
     * <p>배치가 옮겨 주기를 기다리면 "10분" 이 스케줄러 주기만큼 늘어난다. 그래서 행은
     * {@code REQUESTED} 그대로 두고 요청 시각만 과거로 민다.
     */
    @Test
    void anOverdueRequestIsNeitherQueuedNorAcceptable() throws Exception {
        Fixture booth = leasedBoothWithStaff("만료요청");
        Member visitor = member("기다린방문자");
        Long requestId = requestIdOf(request(visitor, booth.boothId()).andExpect(status().isCreated()));
        jdbc.update("UPDATE consultations SET requested_at = now() - interval '11 minutes' WHERE id = ?",
                requestId);

        mockMvc.perform(get("/api/v1/booths/{id}/consultation/requests", booth.boothId())
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/v1/consultation/requests/{id}/accept", requestId)
                        .header("Authorization", booth.staff().bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSULTATION_NOT_REQUESTED"));
        assertEquals("REQUESTED", statusOf(requestId),
                "거부는 읽는 쪽 판정이다 — 수락 경로가 상태를 바꾸지는 않는다.");
    }

    @Test
    void theSweeperMovesOverdueRequestsToExpired() throws Exception {
        Fixture booth = leasedBoothWithStaff("스위퍼");
        Member visitor = member("스위퍼방문자");
        Long requestId = requestIdOf(request(visitor, booth.boothId()).andExpect(status().isCreated()));
        jdbc.update("UPDATE consultations SET requested_at = now() - interval '11 minutes' WHERE id = ?",
                requestId);

        consultationService.expireStale();

        assertEquals("EXPIRED", statusOf(requestId));
    }

    @Test
    void boothIdIsRequired() throws Exception {
        Member visitor = member("본문없음");

        mockMvc.perform(post("/api/v1/consultation/requests")
                        .header("Authorization", visitor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("boothId"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ResultActions request(Member visitor, Long boothId) throws Exception {
        return request(visitor, boothId, null);
    }

    private ResultActions request(Member visitor, Long boothId, String conversationId) throws Exception {
        String body = conversationId == null
                ? "{\"boothId\":" + boothId + "}"
                : "{\"boothId\":" + boothId + ",\"conversationId\":\"" + conversationId + "\"}";
        return mockMvc.perform(post("/api/v1/consultation/requests")
                .header("Authorization", visitor.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Long requestIdOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return Long.valueOf(jsonMapper.readTree(body).get("requestId").asString());
    }

    private String statusOf(Long consultationId) {
        return jdbc.queryForObject("SELECT status FROM consultations WHERE id = ?",
                String.class, consultationId);
    }

    /** 임대된 부스와 그 부스의 상담원 한 명. 상담원도 수락할 수 있다 — 편집 권한과 무관하다. */
    private Fixture leasedBoothWithStaff(String prefix) {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(ownerId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        Member staff = member(prefix + "직원");
        jdbc.update("INSERT INTO booth_staffs(booth_id, user_id, role) VALUES(?, ?, 'CONSULTANT')",
                boothId, staff.userId());
        return new Fixture(boothId, staff);
    }

    private Member member(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        return new Member(userId, users.findById(userId).orElseThrow().getNickname(), bearerFor(userId));
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private String guestBearer() {
        return "Bearer " + accessTokens.issueGuestToken().token();
    }

    private record Fixture(Long boothId, Member staff) { }

    private record Member(Long userId, String nickname, String bearer) { }
}
