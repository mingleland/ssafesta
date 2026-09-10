package com.example.ssafesta.survey;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 부스에 속하지 않는 이벤트 설문 (S15P21A604-621, GitLab #173 · 계약 §11).
 *
 * <p>이 스위트가 지키는 것은 <b>두 축이 서로를 침범하지 않는다</b>는 것이다. 같은 표·같은 제출
 * 경로를 쓰면서 부스 관문만 건너뛰므로, 한쪽 경로가 다른 쪽 자원을 집어 들면 조용히 틀린다 —
 * 이벤트 설문 제출이 404 가 되거나, 부스 결과 API 가 이벤트 id 로 500 이 나는 식이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EventSurveyApiIntegrationTest {

    /** V29 가 시드한 이벤트 식별자. FE 도 같은 문자열을 단일 상수로 들고 있다 (GitLab #173 Q4). */
    private static final String EVENT_KEY = "SSAFESTA_2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private SurveyRepository surveys;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlotsAndClearResponses() {
        releaseAllSlots(jdbc);
        // 시드 설문은 스위트 전체가 공유한다 — 1인 1응답이라 앞 테스트의 응답이 남으면
        // 다음 테스트가 처음부터 "이미 참여함" 을 본다.
        jdbc.update("DELETE FROM survey_answer_options WHERE answer_id IN (SELECT sa.id FROM survey_answers sa"
                + " JOIN survey_responses sr ON sr.id = sa.response_id WHERE sr.survey_id = ?)", eventSurveyId());
        jdbc.update("DELETE FROM survey_answers WHERE response_id IN"
                + " (SELECT id FROM survey_responses WHERE survey_id = ?)", eventSurveyId());
        jdbc.update("DELETE FROM survey_responses WHERE survey_id = ?", eventSurveyId());
    }

    // ── 스키마 ──────────────────────────────────────────────────────────────

    /** 시드가 실제로 부스 밖에 있다. booth_id 가 채워져 있으면 이 기능 전체가 무의미하다. */
    @Test
    void theSeededEventSurveyBelongsToNoBooth() {
        Survey seeded = surveys.findBySurveyKey(EVENT_KEY).orElseThrow();

        assertEquals(EVENT_KEY, seeded.getSurveyKey());
        assertEquals(null, seeded.getBoothId(), "이벤트 설문은 부스에 속하지 않는다");
        assertEquals(null, seeded.getCreatedByUserId(), "만든 사람이 없다 — 시드다");
        assertEquals(0, seeded.getRewardCoin(), "코인을 주지 않는다. 그래도 회원 전용이다");
        org.junit.jupiter.api.Assertions.assertTrue(seeded.isEvent());
    }

    /**
     * 두 축은 배타적이고 부스 쪽 불변식은 그대로다 (ck_surveys_scope).
     *
     * <p>CHECK 를 쓰지 않고 서비스에서만 막으면, 시드나 운영 SQL 이 섞인 행을 만들 수 있고 그
     * 행은 읽는 쪽마다 다르게 해석된다.
     */
    @Test
    void theScopeCheckRefusesMixedAndAuthorlessRows() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                        "INSERT INTO surveys (booth_id, survey_key, title, reward_coin, status, created_by_user_id)"
                                + " VALUES (1, 'BOTH_AXES', '둘 다', 0, 'OPEN', 1)"),
                "부스와 이벤트를 동시에 가질 수 없다");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                        "INSERT INTO surveys (booth_id, survey_key, title, reward_coin, status, created_by_user_id)"
                                + " VALUES (NULL, NULL, '어느 쪽도 아님', 0, 'OPEN', 1)"),
                "둘 다 없는 설문은 누구의 것도 아니다");

        Long ownerId = createMemberWithWallet(users, wallets, "작성자없음");
        Long boothId = booths.save(new Booth(ownerId, "작성자없음 부스")).getId();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                        "INSERT INTO surveys (booth_id, survey_key, title, reward_coin, status, created_by_user_id)"
                                + " VALUES (?, NULL, '작성자 없는 부스 설문', 0, 'OPEN', NULL)", boothId),
                "부스 설문의 작성자 불변식은 그대로여야 한다 — created_by_user_id 를 nullable 로"
                        + " 내린 것은 이벤트 행 때문이다");
    }

    /** 이벤트 하나당 설문 하나 (ux_surveys_key). */
    @Test
    void theEventKeyIsUnique() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO surveys (booth_id, survey_key, title, reward_coin, status, created_by_user_id)"
                        + " VALUES (NULL, ?, '중복 이벤트', 0, 'OPEN', NULL)", EVENT_KEY));
    }

    // ── run 조회 ────────────────────────────────────────────────────────────

    @Test
    void aMemberReadsTheEventSurveyWithoutAnyBoothGate() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "이벤트조회");
        long questionId = seedQuestion("가장 좋았던 부스는?");

        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", bearerFor(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surveyKey").value(EVENT_KEY))
                .andExpect(jsonPath("$.surveyId").value(eventSurveyId()))
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.rewardCoin").value(0))
                // 보상 0 인데 회원 전용이다. rewardCoin 으로 판정하면 게스트에게 열린다.
                .andExpect(jsonPath("$.memberOnly").value(true))
                .andExpect(jsonPath("$.responded").value(Matchers.nullValue()))
                // 문항은 부스 설문과 같은 모양이다 — 클라이언트가 한 벌로 그린다.
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.questions[0].questionId").value(questionId))
                .andExpect(jsonPath("$.questions[0].prompt").value("가장 좋았던 부스는?"))
                .andExpect(jsonPath("$.questions[0].type").value("SHORT_TEXT"));
    }

    /** 참여 뒤 재진입은 제출 전에 "이미 참여함" 을 안다 — 그 한 값 때문에 왕복을 늘리지 않는다. */
    @Test
    void reEntryCarriesTheMembersOwnResponse() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "재진입");
        seedQuestion("재진입 문항");
        mockMvc.perform(submitEmpty(bearerFor(memberId))).andExpect(status().isCreated());

        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", bearerFor(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responded.responseId").isNumber())
                .andExpect(jsonPath("$.responded.submittedAt").isString());
    }

    /** 남의 참여가 내 화면을 "이미 참여함" 으로 만들면 안 된다. */
    @Test
    void anotherMembersResponseDoesNotShowAsMine() throws Exception {
        Long first = createMemberWithWallet(users, wallets, "먼저참여");
        Long second = createMemberWithWallet(users, wallets, "아직참여");
        mockMvc.perform(submitEmpty(bearerFor(first))).andExpect(status().isCreated());

        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", bearerFor(second)))
                .andExpect(jsonPath("$.responded").value(Matchers.nullValue()));
    }

    @Test
    void aGuestCannotReadItAndAnAnonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", guestBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));

        mockMvc.perform(get(runPath(EVENT_KEY))).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownEventKeyIsNotFound() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "없는키");

        mockMvc.perform(get(runPath("NO_SUCH_EVENT")).header("Authorization", bearerFor(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
    }

    // ── 제출 ────────────────────────────────────────────────────────────────

    /**
     * 제출은 부스 설문과 같은 경로를 탄다. 부스 관문만 건너뛴다.
     *
     * <p>이 테스트가 잡는 진짜 함정은 {@code findBoothIdById} 다 — 스칼라 projection 이라
     * {@code booth_id} 가 NULL 이면 "설문이 없다" 와 같은 empty 로 오고, 가르지 않으면 이벤트
     * 설문 제출이 404 가 된다.
     */
    @Test
    void aMemberSubmitsWithoutAnyBoothGate() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "이벤트제출");
        seedQuestion("제출 문항");

        mockMvc.perform(submitEmpty(bearerFor(memberId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responseId").isNumber())
                .andExpect(jsonPath("$.rewardedCoin").value(0));

        assertEquals(1, countResponses(), "응답이 저장돼야 추첨 대상이 남는다");
    }

    /** 보상이 0 이어도 게스트는 막힌다 — 추첨이 참여자를 특정해야 한다. */
    @Test
    void aGuestIsRefusedEvenThoughTheRewardIsZero() throws Exception {
        seedQuestion("게스트 문항");

        mockMvc.perform(submitEmpty(guestBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));

        assertEquals(0, countResponses());
    }

    @Test
    void aSecondSubmissionFromTheSameMemberIsRefused() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "중복참여");
        seedQuestion("중복 문항");
        mockMvc.perform(submitEmpty(bearerFor(memberId))).andExpect(status().isCreated());

        mockMvc.perform(submitEmpty(bearerFor(memberId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_ALREADY_RESPONDED"));

        assertEquals(1, countResponses());
    }

    @Test
    void anUnknownSurveyIdIsStillNotFound() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "없는설문");

        mockMvc.perform(post("/api/v1/surveys/{id}/responses", 9_999_999L)
                        .header("Authorization", bearerFor(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
    }

    // ── 반대 방향 가드 ──────────────────────────────────────────────────────

    /**
     * 부스 결과 경로에 이벤트 설문 id 를 넣어도 500 이 나지 않는다.
     *
     * <p>{@code requireEditableSurvey} 가 {@code boothId} 가 null 인 채로 가드에 내려가면
     * 500 이었다. 응답자 식별은 이벤트 축에만 두기로 했으므로(FR-009·SC-003 익명 계약은 부스
     * 쪽에 그대로) 이 경로에서 이벤트 설문은 <b>없는 자원</b>이다.
     */
    @Test
    void theBoothResultsPathRefusesAnEventSurveyId() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "결과침범");
        String bearer = bearerFor(memberId);

        mockMvc.perform(get("/api/v1/surveys/{id}/results", eventSurveyId()).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/surveys/{id}/text-answers", eventSurveyId())
                        .header("Authorization", bearer)
                        .param("questionId", "1").param("page", "0").param("size", "20"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
    }

    /** 반대쪽도 막힌다 — 부스 설문은 이벤트 경로에서 찾히지 않는다 (키가 없다). */
    @Test
    void theEventPathDoesNotReachABoothSurvey() throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, "부스침범");
        Long boothId = booths.save(new Booth(ownerId, "부스침범 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        publishLayout(mockMvc, boothId, bearerFor(ownerId));
        mockMvc.perform(put("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearerFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"부스 설문","rewardCoin":0,
                                 "questions":[{"type":"SHORT_TEXT","prompt":"부스 문항","required":false}]}"""))
                .andExpect(status().isOk());

        Long boothSurveyId = surveys.findByBoothId(boothId).orElseThrow().getId();
        assertEquals(null, surveys.findById(boothSurveyId).orElseThrow().getSurveyKey(),
                "부스 설문에는 이벤트 키가 붙지 않는다");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seedQuestion(String prompt) {
        jdbc.update("INSERT INTO survey_questions (survey_id, question_type, question_text,"
                        + " is_required, display_order) VALUES (?, 'SHORT_TEXT', ?, FALSE, ?)",
                eventSurveyId(), prompt, nextDisplayOrder());
        return jdbc.queryForObject(
                "SELECT id FROM survey_questions WHERE survey_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, eventSurveyId());
    }

    private int nextDisplayOrder() {
        Integer max = jdbc.queryForObject(
                "SELECT coalesce(max(display_order), 0) FROM survey_questions WHERE survey_id = ?",
                Integer.class, eventSurveyId());
        return (max == null ? 0 : max) + 1;
    }

    private Long eventSurveyId() {
        return surveys.findBySurveyKey(EVENT_KEY).orElseThrow().getId();
    }

    private long countResponses() {
        Long found = jdbc.queryForObject("SELECT count(*) FROM survey_responses WHERE survey_id = ?",
                Long.class, eventSurveyId());
        return found == null ? 0 : found;
    }

    private org.springframework.test.web.servlet.RequestBuilder submitEmpty(String bearer) {
        return post("/api/v1/surveys/{id}/responses", eventSurveyId())
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[]}");
    }

    private String runPath(String surveyKey) {
        return "/api/v1/event-surveys/" + surveyKey + "/run";
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private String guestBearer() {
        return "Bearer " + accessTokens.issueGuestToken().token();
    }
}
