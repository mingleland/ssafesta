package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 부스 방문·체류 계측 (S15P21A604-240, GitLab #94).
 *
 * <p>이 기능의 값어치는 <b>세는 방식</b>에 있다. 방문이 기록되는지만 보면, 재전송이 방문 수를
 * 부풀리거나 열린 방문이 평균 체류를 끌어올려도 전부 초록이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothVisitApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    // ── 기록 ────────────────────────────────────────────────────────────────

    @Test
    void aMemberVisitIsRecordedAndClosed() throws Exception {
        Booth booth = publishedBooth("방문기록");
        String visitor = bearerFor(createMemberWithWallet(users, wallets, "방문자"));

        Long visitId = visitIdOf(enter(booth, visitor)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enteredAt").exists()));

        mockMvc.perform(post("/api/v1/booths/{boothId}/visits/{visitId}/exit", booth.getId(), visitId)
                        .header("Authorization", visitor))
                .andExpect(status().isNoContent());

        assertEquals(1, count("SELECT count(*) FROM booth_visit_events WHERE booth_id = ? "
                + "AND exited_at IS NOT NULL", booth.getId()));
    }

    /** 게스트도 센다 — 빼면 실제 트래픽을 절반만 보게 된다. */
    @Test
    void aGuestVisitIsAlsoRecorded() throws Exception {
        Booth booth = publishedBooth("게스트방문");

        enter(booth, "Bearer " + accessTokens.issueGuestToken().token())
                .andExpect(status().isCreated());

        assertEquals(1, count("SELECT count(*) FROM booth_visit_events WHERE booth_id = ? "
                + "AND visitor_user_id IS NULL", booth.getId()));
    }

    /**
     * 입장 신호가 두 번 와도 방문은 하나다.
     *
     * <p>브릿지 이벤트는 재전송될 수 있다. 그때마다 행이 늘면 방문 수가 실제보다 커지고, 그 수로
     * "Coin 시스템이 방문을 늘렸는가" 를 판단하게 된다.
     */
    @Test
    void aRepeatedEnterDoesNotCreateASecondVisit() throws Exception {
        Booth booth = publishedBooth("재전송");
        String visitor = bearerFor(createMemberWithWallet(users, wallets, "재전송방문자"));

        Long first = visitIdOf(enter(booth, visitor).andExpect(status().isCreated()));
        Long second = visitIdOf(enter(booth, visitor).andExpect(status().isCreated()));

        assertEquals(first, second, "같은 방문이 돌아와야 합니다.");
        assertEquals(1, count("SELECT count(*) FROM booth_visit_events WHERE booth_id = ?",
                booth.getId()));
    }

    /** 닫은 뒤 다시 들어오면 그것은 새 방문이다. */
    @Test
    void enteringAgainAfterLeavingIsANewVisit() throws Exception {
        Booth booth = publishedBooth("재방문");
        String visitor = bearerFor(createMemberWithWallet(users, wallets, "재방문자"));
        Long first = visitIdOf(enter(booth, visitor).andExpect(status().isCreated()));
        mockMvc.perform(post("/api/v1/booths/{b}/visits/{v}/exit", booth.getId(), first)
                .header("Authorization", visitor)).andExpect(status().isNoContent());

        Long second = visitIdOf(enter(booth, visitor).andExpect(status().isCreated()));

        assertEquals(2, count("SELECT count(*) FROM booth_visit_events WHERE booth_id = ?",
                booth.getId()));
        assertEquals(false, first.equals(second));
    }

    @Test
    void anotherMembersVisitCannotBeClosed() throws Exception {
        Booth booth = publishedBooth("남의방문");
        String visitor = bearerFor(createMemberWithWallet(users, wallets, "주인방문자"));
        String other = bearerFor(createMemberWithWallet(users, wallets, "엉뚱한방문자"));
        Long visitId = visitIdOf(enter(booth, visitor).andExpect(status().isCreated()));

        mockMvc.perform(post("/api/v1/booths/{b}/visits/{v}/exit", booth.getId(), visitId)
                        .header("Authorization", other))
                .andExpect(status().isForbidden());
    }

    /**
     * 채널은 서버가 정한다 — 본문이 없어도, 빈 객체여도 기록된다.
     *
     * <p>예전에는 {@code worldChannel} 이 필수라 둘 다 400 이었다. 클라이언트가 가질 수 없는 값을
     * 요구하던 것이고(계약의 {@code F11-CH01} 은 실제 채널 식별자와 한 번도 맞지 않았다), 그래서
     * FE 가 이 경로를 아예 부르지 못했다.
     *
     * <p><b>두 요청을 서로 다른 방문자로 보낸다.</b> 같은 회원으로 두 번 보내면 재전송 병합
     * (`findOpenVisit`)에 걸려 두 번째가 저장 경로를 타지 않는다 — 그러면 "본문 없이도 저장된다" 를
     * 증명하지 못한다.
     */
    @Test
    void theChannelComesFromTheServerSoNoBodyIsNeeded() throws Exception {
        Booth booth = publishedBooth("본문없음");
        String withoutBody = bearerFor(createMemberWithWallet(users, wallets, "본문없는방문자"));
        String withEmptyBody = bearerFor(createMemberWithWallet(users, wallets, "빈본문방문자"));

        mockMvc.perform(post("/api/v1/booths/{id}/visits", booth.getId())
                        .header("Authorization", withoutBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/booths/{id}/visits", booth.getId())
                        .header("Authorization", withEmptyBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        assertEquals(2, count("SELECT count(*) FROM booth_visit_events WHERE booth_id = ?",
                booth.getId()));
        assertEquals(2, count("SELECT count(*) FROM booth_visit_events"
                + " WHERE booth_id = ? AND world_channel = '11F-01'", booth.getId()));
    }

    /** 들어갈 수 없는 부스의 방문은 기록하지 않는다 — 집계가 오염된다. */
    @Test
    void anExpiredBoothRecordsNoVisit() throws Exception {
        Booth booth = publishedBooth("만료부스");
        String visitor = bearerFor(createMemberWithWallet(users, wallets, "늦은방문자"));
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE booth_id = ?", booth.getId());

        enter(booth, visitor).andExpect(status().isConflict());

        assertEquals(0, count("SELECT count(*) FROM booth_visit_events WHERE booth_id = ?",
                booth.getId()));
    }

    // ── 집계 ────────────────────────────────────────────────────────────────

    /**
     * <b>평균 체류는 닫힌 방문만 센다.</b>
     *
     * <p>열린 방문을 "지금까지" 로 계산하면 창을 열어 둔 사람 하나가 평균을 끌어올린다. 대신 열린
     * 수를 따로 돌려주어 읽는 쪽이 판단하게 한다.
     */
    @Test
    void averageDwellCountsOnlyClosedVisits() throws Exception {
        Booth booth = publishedBooth("집계");
        Long ownerId = booth.getOwnerUserId();
        seedVisit(booth.getId(), createMemberWithWallet(users, wallets, "체류60"), 60);
        seedVisit(booth.getId(), createMemberWithWallet(users, wallets, "체류120"), 120);
        seedOpenVisit(booth.getId(), createMemberWithWallet(users, wallets, "안닫힌방문"));

        mockMvc.perform(get("/api/v1/booths/{id}/visit-metrics", booth.getId())
                        .header("Authorization", bearerFor(ownerId))
                        .param("from", "2000-01-01T00:00:00Z")
                        .param("to", "2100-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").value(3))
                .andExpect(jsonPath("$.uniqueVisitors").value(3))
                .andExpect(jsonPath("$.averageDwellSeconds").value(90))
                .andExpect(jsonPath("$.openVisits").value(1));
    }

    /** 기간 밖 방문은 빠진다 — 그렇지 않으면 from/to 가 장식이다. */
    @Test
    void visitsOutsideTheWindowAreExcluded() throws Exception {
        Booth booth = publishedBooth("기간");
        seedVisit(booth.getId(), createMemberWithWallet(users, wallets, "옛날방문"), 60);
        jdbc.update("UPDATE booth_visit_events SET entered_at = now() - interval '30 days' "
                + "WHERE booth_id = ?", booth.getId());

        mockMvc.perform(get("/api/v1/booths/{id}/visit-metrics", booth.getId())
                        .header("Authorization", bearerFor(booth.getOwnerUserId()))
                        .param("from", "2100-01-01T00:00:00Z")
                        .param("to", "2100-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").value(0))
                .andExpect(jsonPath("$.averageDwellSeconds").value(0));
    }

    @Test
    void anOutsiderCannotReadTheMetrics() throws Exception {
        Booth booth = publishedBooth("남의집계");
        String outsider = bearerFor(createMemberWithWallet(users, wallets, "외부인"));

        mockMvc.perform(get("/api/v1/booths/{id}/visit-metrics", booth.getId())
                        .header("Authorization", outsider)
                        .param("from", "2000-01-01T00:00:00Z")
                        .param("to", "2100-01-01T00:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBackwardsWindowIsRejected() throws Exception {
        Booth booth = publishedBooth("거꾸로");

        mockMvc.perform(get("/api/v1/booths/{id}/visit-metrics", booth.getId())
                        .header("Authorization", bearerFor(booth.getOwnerUserId()))
                        .param("from", "2100-01-02T00:00:00Z")
                        .param("to", "2100-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ResultActions enter(Booth booth, String bearer) throws Exception {
        return mockMvc.perform(post("/api/v1/booths/{id}/visits", booth.getId())
                .header("Authorization", bearer));
    }

    private Long visitIdOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return Long.valueOf(jsonMapper.readTree(body).get("visitId").asString());
    }

    private void seedVisit(Long boothId, Long userId, int dwellSeconds) {
        jdbc.update("INSERT INTO booth_visit_events(booth_id, visitor_user_id, world_channel,"
                + " entered_at, exited_at) VALUES(?, ?, '11F-01', now(), now() + (? || ' seconds')::interval)",
                boothId, userId, dwellSeconds);
    }

    private void seedOpenVisit(Long boothId, Long userId) {
        jdbc.update("INSERT INTO booth_visit_events(booth_id, visitor_user_id, world_channel,"
                + " entered_at) VALUES(?, ?, '11F-01', now())", boothId, userId);
    }

    /**
     * 공개본이 있는 임대 부스. 공개 여부까지 갖추는 이유는 방문 기록이
     * {@code requireVisitorVisible} 을 지나기 때문이다 — 방문자가 실제로 볼 수 있는 부스만 센다.
     *
     * <p>{@code published_layout_version} 을 직접 쓰지 않는다. 그 컬럼은 공개본 표를 가리키는
     * 복합 외래키라 숫자만 넣으면 제약 위반이다 — 실제 공개 경로를 태운다.
     */
    private Booth publishedBooth(String prefix) throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Booth booth = booths.save(new Booth(ownerId, prefix + " 부스"));
        BoothLayoutTestSupport.grantLease(jdbc, booth.getId(), ownerId);
        BoothLayoutTestSupport.publishLayout(mockMvc, booth.getId(), bearerFor(ownerId));
        return booths.findById(booth.getId()).orElseThrow();
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
