package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 부스 운영 요약 (spec 015 US1, S15P21A604-501).
 *
 * <p>이 화면의 값어치는 <b>숫자의 정의</b>에 있다. 응답이 200 이고 필드가 있는지만 보면, 셀 원천이
 * 없는 칸이 0 으로 채워져도, 상담을 종료 시각 기준으로 세도, 남의 부스 설문이 섞여도 전부 초록이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothDashboardApiIntegrationTest {

    private static final String ALL_TIME_FROM = "2000-01-01T00:00:00Z";
    private static final String ALL_TIME_TO = "2100-01-01T00:00:00Z";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    /** 세 원천이 한 응답에 모인다 — 그것이 이 endpoint 의 존재 이유다. */
    @Test
    void theSummaryGathersVisitsConsultationsAndSurveyResponses() throws Exception {
        Booth booth = publishedBooth("요약");
        seedClosedVisit(booth.getId(), member("요약방문1"), 60);
        seedClosedVisit(booth.getId(), member("요약방문2"), 120);
        seedConsultation(booth.getId(), member("요약상담1"), "ENDED");
        seedConsultation(booth.getId(), member("요약상담2"), "EXPIRED");
        Long surveyId = seedSurvey(booth.getId(), booth.getOwnerUserId());
        seedResponse(surveyId, member("요약응답1"));

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").value(2))
                .andExpect(jsonPath("$.uniqueVisitors").value(2))
                .andExpect(jsonPath("$.averageDwellSeconds").value(90))
                .andExpect(jsonPath("$.openVisits").value(0))
                .andExpect(jsonPath("$.consultations").value(2))
                .andExpect(jsonPath("$.consultationsEnded").value(1))
                .andExpect(jsonPath("$.surveyResponses").value(1));
    }

    /**
     * <b>{@code null} 은 0 이 아니다.</b>
     *
     * <p>AI 이용은 대화 기록 표가 아직 없다. 0 으로 채우면 FE 는 "AI 이용 0건" 카드를 영원히 띄우고,
     * 아무도 그것이 집계 결과가 아니라 없는 것이라는 사실을 모른다.
     *
     * <p>코인 칸은 이제 {@code null} 이 아니다 — 셀 원천이 있다(쓴 코인·뿌린 코인). 그래서 이 둘은
     * 0 이 정상이고, 그 사실도 함께 고정한다.
     */
    @Test
    void theSourcelessMetricIsNullWhileCoinIsZero() throws Exception {
        Booth booth = publishedBooth("원천없음");

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiUsages").value(nullValue()))
                .andExpect(jsonPath("$.leaseCostCoin").value(0))
                .andExpect(jsonPath("$.surveyRewardCoin").value(0));
    }

    /** 데이터 0건 부스가 오류 없이 0 을 보인다 (SC-002) — 평균은 0 으로 나누지 않는다. */
    @Test
    void anEmptyBoothReportsZerosWithoutFailing() throws Exception {
        Booth booth = publishedBooth("빈부스");

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").value(0))
                .andExpect(jsonPath("$.averageDwellSeconds").value(0))
                .andExpect(jsonPath("$.consultations").value(0))
                .andExpect(jsonPath("$.surveyResponses").value(0));
    }

    /**
     * 상담은 <b>요청 시각</b> 기준으로 센다 — 아직 안 끝난 상담도 요청한 기간에 들어간다.
     *
     * <p>종료 시각 기준으로 세면 진행 중인 상담이 어느 기간에도 안 들어가고, 그러면 기간별 합이
     * 전체보다 작아진다. 요청은 있었는데 아무 데도 없는 상담이 생긴다.
     */
    @Test
    void anUnfinishedConsultationStillCountsInTheRequestedWindow() throws Exception {
        Booth booth = publishedBooth("진행중");
        seedConsultation(booth.getId(), member("진행중방문자"), "ACCEPTED");

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations").value(1))
                .andExpect(jsonPath("$.consultationsEnded").value(0));
    }

    /** 기간 밖 상담은 빠진다 — 그렇지 않으면 from/to 가 장식이다. */
    @Test
    void consultationsOutsideTheWindowAreExcluded() throws Exception {
        Booth booth = publishedBooth("기간밖");
        seedConsultation(booth.getId(), member("옛날방문자"), "ENDED");
        jdbc.update("UPDATE consultations SET requested_at = now() - interval '30 days' "
                + "WHERE booth_id = ?", booth.getId());

        summary(booth, bearerFor(booth.getOwnerUserId()), "2100-01-01T00:00:00Z", "2100-01-02T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations").value(0));
    }

    /** 다른 부스의 설문 응답이 섞이지 않는다 (FR-008). */
    @Test
    void anotherBoothsSurveyResponsesAreNotCounted() throws Exception {
        Booth mine = publishedBooth("내부스");
        Booth theirs = publishedBooth("남부스");
        seedResponse(seedSurvey(theirs.getId(), theirs.getOwnerUserId()), member("남의응답자"));

        summary(mine, bearerFor(mine.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surveyResponses").value(0));
    }


    /**
     * <b>코인 칸은 "수익" 이 아니라 쓴 것과 뿌린 것이다.</b>
     *
     * <p>부스로 코인이 들어오는 경로가 없기 때문이다 — 임대료는 소유자가 <i>내는</i> 돈이고, 설문
     * 보상은 차감되는 지갑 없이 <i>발행</i>된다. 임대료를 음수로, 보상을 양수로 그대로 흘리면 읽는
     * 쪽이 부호를 해석해야 하므로 둘 다 양수로 준다.
     */
    @Test
    void theSummaryReportsCoinSpentAndCoinIssued() throws Exception {
        Booth booth = publishedBooth("코인");
        chargeLease(booth, 100);
        issueSurveyReward(booth, 55);

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseCostCoin").value(100))
                .andExpect(jsonPath("$.surveyRewardCoin").value(55));
    }

    /**
     * 다른 부스의 코인이 섞이지 않는다.
     *
     * <p>원장에는 부스 컬럼이 없다 — {@code reference_id} 로 임대·설문을 되짚어야 부스를 안다.
     * 그 되짚기가 빠지면 플랫폼 전체 코인이 한 부스의 숫자로 나온다.
     */
    @Test
    void anotherBoothsCoinIsNotCounted() throws Exception {
        Booth mine = publishedBooth("내코인");
        Booth theirs = publishedBooth("남코인");
        chargeLease(theirs, 100);
        issueSurveyReward(theirs, 55);

        summary(mine, bearerFor(mine.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseCostCoin").value(0))
                .andExpect(jsonPath("$.surveyRewardCoin").value(0));
    }

    /** 기간 밖 원장은 빠진다 — 그렇지 않으면 from/to 가 코인 칸에서만 장식이 된다. */
    @Test
    void coinOutsideTheWindowIsExcluded() throws Exception {
        Booth booth = publishedBooth("코인기간");
        chargeLease(booth, 100);
        issueSurveyReward(booth, 55);
        jdbc.update("UPDATE coin_ledger_entries SET created_at = now() - interval '30 days'");

        summary(booth, bearerFor(booth.getOwnerUserId()), "2100-01-01T00:00:00Z", "2100-01-02T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseCostCoin").value(0))
                .andExpect(jsonPath("$.surveyRewardCoin").value(0));
    }

    /** 다른 사유의 원장은 세지 않는다 — 미니게임 보상이 설문 보상으로 잡히면 안 된다. */
    @Test
    void unrelatedLedgerReasonsAreNotCounted() throws Exception {
        Booth booth = publishedBooth("다른사유");
        wallets.credit(new CoinCreditCommand(booth.getOwnerUserId(), LedgerEntryType.REWARD, 77,
                CoinReason.MINIGAME_REWARD, "MINIGAME_SESSION", "1", "t-minigame-" + booth.getId()));

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surveyRewardCoin").value(0))
                .andExpect(jsonPath("$.leaseCostCoin").value(0));
    }

    /**
     * 게이트는 <b>역할</b>을 본다.
     *
     * <p>{@code CONSULTANT} 는 상담을 하지 부스 운영 지표를 보지 않는다. 직원 행이 있다는 것만으로
     * 통과시키면 상담원이 방문·설문·상담 전체 통계를 읽는다.
     */
    @Test
    void aConsultantCannotReadTheSummaryButAContentEditorCan() throws Exception {
        Booth booth = publishedBooth("역할");
        Long consultant = member("상담원");
        Long editor = member("편집자");
        addStaff(booth.getId(), consultant, "CONSULTANT");
        addStaff(booth.getId(), editor, "CONTENT_EDITOR");

        summary(booth, bearerFor(consultant)).andExpect(status().isForbidden());
        summary(booth, bearerFor(editor)).andExpect(status().isOk());
    }

    @Test
    void anOutsiderIsRefused() throws Exception {
        Booth booth = publishedBooth("외부인");

        summary(booth, bearerFor(member("남남"))).andExpect(status().isForbidden());
    }

    @Test
    void aBackwardsWindowIsRejected() throws Exception {
        Booth booth = publishedBooth("거꾸로");

        summary(booth, bearerFor(booth.getOwnerUserId()), ALL_TIME_TO, ALL_TIME_FROM)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    /**
     * 임대가 끝난 부스도 지난 기간을 볼 수 있다 (C-03).
     *
     * <p>행사가 끝난 뒤에 정산을 한다. 임대 만료로 지표까지 닫으면 운영자는 자기 행사의 결과를 볼
     * 기회가 없다.
     */
    @Test
    void anExpiredLeaseStillShowsPastNumbers() throws Exception {
        Booth booth = publishedBooth("만료");
        seedClosedVisit(booth.getId(), member("만료전방문"), 30);
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE booth_id = ?", booth.getId());

        summary(booth, bearerFor(booth.getOwnerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").value(1));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ResultActions summary(Booth booth, String bearer) throws Exception {
        return summary(booth, bearer, ALL_TIME_FROM, ALL_TIME_TO);
    }

    private ResultActions summary(Booth booth, String bearer, String from, String to) throws Exception {
        return mockMvc.perform(get("/api/v1/booths/{id}/dashboard/summary", booth.getId())
                .header("Authorization", bearer)
                .param("from", from)
                .param("to", to));
    }

    private Long member(String prefix) {
        return createMemberWithWallet(users, wallets, prefix);
    }

    /** 이 부스의 임대료를 실제 원장 어휘로 태운다 — 참조는 그 부스의 임대 행 id 다. */
    private void chargeLease(Booth booth, int amount) {
        Long leaseId = jdbc.queryForObject(
                "SELECT id FROM booth_leases WHERE booth_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, booth.getId());
        wallets.spend(new CoinSpendCommand(booth.getOwnerUserId(), amount, CoinReason.LEASE_PAYMENT,
                CoinReason.LEASE_REFERENCE_TYPE, String.valueOf(leaseId), "t-lease-" + leaseId));
    }

    /** 이 부스 설문이 응답자에게 보상을 발행한다 — 차감되는 지갑은 없다. */
    private void issueSurveyReward(Booth booth, int amount) {
        Long surveyId = seedSurvey(booth.getId(), booth.getOwnerUserId());
        Long respondent = member("보상받는이" + surveyId);
        wallets.credit(new CoinCreditCommand(respondent, LedgerEntryType.REWARD, amount,
                CoinReason.SURVEY_REWARD, CoinReason.SURVEY_REFERENCE_TYPE,
                String.valueOf(surveyId), "t-reward-" + surveyId));
    }

    private void seedClosedVisit(Long boothId, Long userId, int dwellSeconds) {
        jdbc.update("INSERT INTO booth_visit_events(booth_id, visitor_user_id, world_channel,"
                + " entered_at, exited_at) VALUES(?, ?, '11F-01', now(), now() + (? || ' seconds')::interval)",
                boothId, userId, dwellSeconds);
    }

    private void seedConsultation(Long boothId, Long visitorUserId, String status) {
        jdbc.update("INSERT INTO consultations(booth_id, visitor_user_id, status) VALUES(?, ?, ?)",
                boothId, visitorUserId, status);
    }

    private Long seedSurvey(Long boothId, Long ownerUserId) {
        return jdbc.queryForObject("INSERT INTO surveys(booth_id, title, created_by_user_id, status)"
                + " VALUES(?, '집계용 설문', ?, 'OPEN') RETURNING id", Long.class, boothId, ownerUserId);
    }

    private void seedResponse(Long surveyId, Long respondentUserId) {
        jdbc.update("INSERT INTO survey_responses(survey_id, respondent_user_id) VALUES(?, ?)",
                surveyId, respondentUserId);
    }

    private void addStaff(Long boothId, Long userId, String role) {
        jdbc.update("INSERT INTO booth_staffs(booth_id, user_id, role) VALUES(?, ?, ?)",
                boothId, userId, role);
    }

    /**
     * 공개본이 있는 임대 부스. {@code published_layout_version} 을 직접 쓰지 않는다 — 그 컬럼은
     * 공개본 표를 가리키는 복합 외래키라 숫자만 넣으면 제약 위반이다.
     */
    private Booth publishedBooth(String prefix) throws Exception {
        Long ownerId = member(prefix);
        Booth booth = booths.save(new Booth(ownerId, prefix + " 부스"));
        BoothLayoutTestSupport.grantLease(jdbc, booth.getId(), ownerId);
        BoothLayoutTestSupport.publishLayout(mockMvc, booth.getId(), bearerFor(ownerId));
        return booths.findById(booth.getId()).orElseThrow();
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
