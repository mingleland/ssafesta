package com.example.ssafesta.survey;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설문 응답 제출과 보상 지급 (spec 010 FR-004·FR-005, contracts/survey-api.md §6).
 *
 * <p>S15P21A604-131 · -192.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SurveyResponseApiIntegrationTest {

    /** 6유형을 한 번에 담은 설문. 필수는 앞 셋이다. */
    private static final String SIX_TYPES = """
            {"title":"6유형","rewardCoin":%d,
             "questions":[
               {"type":"SINGLE_CHOICE","prompt":"하나만","required":true,
                "options":[{"label":"A"},{"label":"B"}]},
               {"type":"MULTIPLE_CHOICE","prompt":"여러 개","required":true,
                "options":[{"label":"C"},{"label":"D"},{"label":"E"}]},
               {"type":"RATING","prompt":"별점","required":true,"scale":{"min":1,"max":5}},
               {"type":"SHORT_TEXT","prompt":"단답","required":false},
               {"type":"LONG_TEXT","prompt":"장문","required":false},
               {"type":"APPLICATION","prompt":"지원서","required":false}]}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private SurveyGuestKeySweeper guestKeySweeper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 정상 제출 ───────────────────────────────────────────────────────────

    @Test
    void memberSubmitsAllSixTypesAndEveryAnswerIsStored() throws Exception {
        Survey s = survey("6유형제출", 0);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), """
                        {"answers":[
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"selectedOptionIds":[%d,%d]},
                          {"questionId":%d,"rating":4},
                          {"questionId":%d,"text":"단답입니다"},
                          {"questionId":%d,"text":"장문입니다"},
                          {"questionId":%d,"text":"지원합니다"}]}"""
                        .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.o(1, 2),
                                s.q(2), s.q(3), s.q(4), s.q(5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responseId").isNumber())
                .andExpect(jsonPath("$.rewardedCoin").value(0));

        assertThat(answerCount(s.id)).isEqualTo(6);
        // 복수선택 2개 + 객관식 1개 = 3
        assertThat(selectedOptionCount(s.id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT rating_value FROM survey_answers a "
                        + "JOIN survey_responses r ON r.id = a.response_id "
                        + "WHERE r.survey_id = ? AND a.question_id = ?",
                Short.class, s.id, s.q(2))).isEqualTo((short) 4);
    }

    @Test
    void optionalQuestionsLeftBlankStoreNoRow() throws Exception {
        Survey s = survey("선택건너뜀", 0);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), """
                        {"answers":[
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"rating":3},
                          {"questionId":%d,"text":"   "},
                          {"questionId":%d,"text":""}]}"""
                        .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.q(2), s.q(3), s.q(4))))
                .andExpect(status().isCreated());

        // 공백·빈 문자열은 답하지 않은 것이다 — FE isEmptyAnswer 와 같은 판정
        assertThat(answerCount(s.id)).isEqualTo(3);
    }

    // ── 보상 (-192) ─────────────────────────────────────────────────────────

    @Test
    void memberIsPaidOnceAndTheLedgerRecordsIt() throws Exception {
        Survey s = survey("보상", 5);
        int before = balanceOf(s.ownerId);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardedCoin").value(5));

        assertThat(balanceOf(s.ownerId)).isEqualTo(before + 5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coin_ledger_entries e "
                        + "JOIN wallets w ON w.id = e.wallet_id "
                        + "WHERE w.user_id = ? AND e.reason_type = 'SURVEY_REWARD'",
                Long.class, s.ownerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reward_ledger_entry_id FROM survey_responses "
                + "WHERE survey_id = ?", Long.class, s.id)).isNotNull();
        // spec 003 I-1 — 잔액과 원장 합계가 일치해야 한다
        var wallet = wallets.requireWallet(s.ownerId);
        assertThat((long) wallet.getBalance()).isEqualTo(wallets.ledgerSumOf(wallet.getId()));
    }

    @Test
    void resubmittingNeitherStoresNorPaysTwice() throws Exception {
        Survey s = survey("재제출", 5);
        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isCreated());
        int after = balanceOf(s.ownerId);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_ALREADY_RESPONDED"));

        assertThat(balanceOf(s.ownerId)).isEqualTo(after);
        assertThat(responseCount(s.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coin_ledger_entries e "
                        + "JOIN wallets w ON w.id = e.wallet_id "
                        + "WHERE w.user_id = ? AND e.reason_type = 'SURVEY_REWARD'",
                Long.class, s.ownerId)).isEqualTo(1);
    }

    @Test
    void anUnrewardedSurveyPaysNothingAndSaysSo() throws Exception {
        Survey s = survey("무보상", 0);
        int before = balanceOf(s.ownerId);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardedCoin").value(0));

        assertThat(balanceOf(s.ownerId)).isEqualTo(before);
    }

    // ── 게스트 (C-05) ───────────────────────────────────────────────────────

    @Test
    void guestAnswersAnUnrewardedSurvey() throws Exception {
        Survey s = survey("게스트무보상", 0);

        mockMvc.perform(submit(s, guestBearer(), minimalAnswers(s)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardedCoin").value(0));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM survey_responses "
                + "WHERE survey_id = ? AND respondent_guest_key IS NOT NULL", Long.class, s.id))
                .isEqualTo(1);
    }

    /** 게스트는 지갑이 없다 — 답을 받고 빈손으로 돌려보내지 않고 시작 전에 막는다. */
    @Test
    void guestIsRefusedOnARewardedSurvey() throws Exception {
        Survey s = survey("게스트보상", 5);

        mockMvc.perform(submit(s, guestBearer(), minimalAnswers(s)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));

        assertThat(responseCount(s.id)).isZero();
    }

    @Test
    void theSameGuestTokenMayNotAnswerTwice() throws Exception {
        Survey s = survey("게스트재제출", 0);
        String guest = guestBearer();
        mockMvc.perform(submit(s, guest, minimalAnswers(s))).andExpect(status().isCreated());

        mockMvc.perform(submit(s, guest, minimalAnswers(s)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_ALREADY_RESPONDED"));
    }

    /**
     * 다른 게스트 토큰은 다른 사람으로 통과한다 — 계정 없는 사람을 그 이상 식별할 방법이 없고
     * 계약 §6 이 그 한계를 적어 두었다.
     */
    @Test
    void adifferentGuestTokenIsADifferentRespondent() throws Exception {
        Survey s = survey("게스트둘", 0);
        mockMvc.perform(submit(s, guestBearer(), minimalAnswers(s))).andExpect(status().isCreated());

        mockMvc.perform(submit(s, guestBearer(), minimalAnswers(s)))
                .andExpect(status().isCreated());

        assertThat(responseCount(s.id)).isEqualTo(2);
    }

    // ── 게이트 ──────────────────────────────────────────────────────────────

    @Test
    void aClosedSurveyRefusesSubmission() throws Exception {
        Survey s = survey("마감제출", 0);
        jdbc.update("UPDATE surveys SET ends_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), s.id);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_CLOSED"));
    }

    @Test
    void anExpiredBoothRefusesSubmission() throws Exception {
        Survey s = survey("만료제출", 0);
        expireLease(jdbc, s.boothId);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }

    @Test
    void anUnpublishedBoothRefusesSubmission() throws Exception {
        Survey s = survey("미게시제출", 0, false);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), minimalAnswers(s)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAYOUT_NOT_PUBLISHED"));
    }

    @Test
    void anUnknownSurveyIsNotFound() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "없는설문");

        mockMvc.perform(post("/api/v1/surveys/{id}/responses", 999_999L)
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → 400 field {1}")
    @CsvSource(delimiter = '|', value = {
            "없는 문항|answers[0].questionId|{\"answers\":[{\"questionId\":999999,\"rating\":3}]}",
            "같은 문항 두 번|answers[1].questionId|{\"answers\":[{\"questionId\":Q0,\"selectedOptionIds\":[O00]},{\"questionId\":Q0,\"selectedOptionIds\":[O00]}]}",
            "객관식에 2개|answers[0].selectedOptionIds|{\"answers\":[{\"questionId\":Q0,\"selectedOptionIds\":[O00,O01]}]}",
            "복수선택 중복 id|answers[0].selectedOptionIds|{\"answers\":[{\"questionId\":Q1,\"selectedOptionIds\":[O10,O10]}]}",
            "남의 선택지|answers[0].selectedOptionIds|{\"answers\":[{\"questionId\":Q0,\"selectedOptionIds\":[O10]}]}",
            "별점 범위 밖|answers[0].rating|{\"answers\":[{\"questionId\":Q2,\"rating\":6}]}",
            "별점 문항에 텍스트|answers[0].text|{\"answers\":[{\"questionId\":Q2,\"text\":\"넷\"}]}",
            "텍스트 문항에 별점|answers[0].rating|{\"answers\":[{\"questionId\":Q3,\"rating\":3}]}",
            "객관식에 별점|answers[0].rating|{\"answers\":[{\"questionId\":Q0,\"rating\":3}]}"})
    void rejectsBadAnswersWithTheOffendingField(String label, String field, String template)
            throws Exception {
        Survey s = survey("답검증" + Math.abs(label.hashCode() % 100_000), 0);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), s.fill(template)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    @Test
    void aMissingRequiredAnswerIsRefused() throws Exception {
        Survey s = survey("필수누락", 0);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), """
                        {"answers":[{"questionId":%d,"selectedOptionIds":[%d]}]}"""
                        .formatted(s.q(0), s.o(0, 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("answers"));

        assertThat(responseCount(s.id)).isZero();
    }

    @Test
    void aShortTextOverItsLimitIsRefused() throws Exception {
        Survey s = survey("단답초과", 0);
        String tooLong = "가".repeat(201);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), """
                        {"answers":[
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"rating":3},
                          {"questionId":%d,"text":"%s"}]}"""
                        .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.q(2), s.q(3), tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("answers[3].text"));
    }

    @Test
    void aLongTextUpToItsLimitIsAccepted() throws Exception {
        Survey s = survey("장문경계", 0);

        mockMvc.perform(submit(s, bearerFor(s.ownerId), """
                        {"answers":[
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"rating":3},
                          {"questionId":%d,"text":"%s"}]}"""
                        .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.q(2), s.q(4),
                                "나".repeat(2_000))))
                .andExpect(status().isCreated());
    }

    /**
     * 게스트 세션이 끝나면 그 식별자가 응답에서 사라진다 (헌법 12조).
     *
     * <p>지우는 것은 식별자 한 칸뿐이고 답은 남는다 — 응답은 그것을 수집한 부스의 것이라,
     * 방문자 세션이 끝났다고 운영자의 결과가 사라지면 안 된다.
     *
     * <p>세션 수명({@code app.auth.access-token-ttl})이 지난 것으로 만들기 위해 제출 시각을
     * 뒤로 민다. 스케줄러를 기다리지 않고 같은 메서드를 직접 부른다.
     */
    @Test
    void anExpiredGuestSessionLosesItsKeyButKeepsItsAnswers() throws Exception {
        Survey s = survey("게스트만료", 0);
        mockMvc.perform(submit(s, guestBearer(), """
                        {"answers":[
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"selectedOptionIds":[%d]},
                          {"questionId":%d,"rating":5},
                          {"questionId":%d,"text":"게스트 의견"}]}"""
                        .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.q(2), s.q(4))))
                .andExpect(status().isCreated());
        String key = jdbc.queryForObject(
                "SELECT respondent_guest_key FROM survey_responses WHERE survey_id = ?", String.class, s.id);
        assertThat(key).startsWith("guest:");

        jdbc.update("UPDATE survey_responses SET submitted_at = now() - interval '2 hours' "
                + "WHERE survey_id = ?", s.id);
        guestKeySweeper.clearExpiredGuestKeys();

        Long responseId = jdbc.queryForObject(
                "SELECT id FROM survey_responses WHERE survey_id = ?", Long.class, s.id);
        assertThat(jdbc.queryForObject(
                "SELECT respondent_guest_key FROM survey_responses WHERE id = ?", String.class, responseId))
                .as("접속 토큰 주체가 남아 있으면 안 됩니다 (헌법 12조).")
                .isEqualTo("expired:" + responseId);
        assertThat(answerCount(s.id)).as("답은 그대로여야 합니다 — 부스가 수집한 데이터입니다.")
                .isEqualTo(4);
        assertThat(selectedOptionCount(s.id)).isEqualTo(2);
    }

    /** 아직 살아 있는 세션의 식별자는 건드리지 않는다 — 그동안은 1인 1응답이 그것으로 성립한다. */
    @Test
    void aLiveGuestSessionKeepsItsKey() throws Exception {
        Survey s = survey("게스트유효", 0);
        mockMvc.perform(submit(s, guestBearer(), """
                        {"answers":[{"questionId":%d,"selectedOptionIds":[%d]},
                                    {"questionId":%d,"selectedOptionIds":[%d]},
                                    {"questionId":%d,"rating":3}]}"""
                        .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.q(2))))
                .andExpect(status().isCreated());

        guestKeySweeper.clearExpiredGuestKeys();

        assertThat(jdbc.queryForObject(
                "SELECT respondent_guest_key FROM survey_responses WHERE survey_id = ?", String.class, s.id))
                .as("세션이 살아 있는 동안은 그대로 둬야 합니다.").startsWith("guest:");
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private String minimalAnswers(Survey s) {
        return """
                {"answers":[
                  {"questionId":%d,"selectedOptionIds":[%d]},
                  {"questionId":%d,"selectedOptionIds":[%d]},
                  {"questionId":%d,"rating":5}]}"""
                .formatted(s.q(0), s.o(0, 0), s.q(1), s.o(1, 0), s.q(2));
    }

    private org.springframework.test.web.servlet.RequestBuilder submit(Survey s, String bearer,
                                                                       String body) {
        return post("/api/v1/surveys/{id}/responses", s.id)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Survey survey(String prefix, int rewardCoin) throws Exception {
        return survey(prefix, rewardCoin, true);
    }

    private Survey survey(String prefix, int rewardCoin, boolean publish) throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(ownerId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        if (publish) {
            publishLayout(mockMvc, boothId, bearerFor(ownerId));
        }
        mockMvc.perform(put("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearerFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIX_TYPES.formatted(rewardCoin)))
                .andExpect(status().isOk());

        MvcResult saved = mockMvc.perform(get("/api/v1/booths/{id}/survey", boothId)
                .header("Authorization", bearerFor(ownerId))).andReturn();
        var view = jsonMapper.readTree(saved.getResponse().getContentAsString());
        List<Long> questionIds = new java.util.ArrayList<>();
        List<List<Long>> optionIds = new java.util.ArrayList<>();
        for (var question : view.get("questions")) {
            questionIds.add(question.get("questionId").asLong());
            List<Long> perQuestion = new java.util.ArrayList<>();
            for (var option : question.get("options")) {
                perQuestion.add(option.get("optionId").asLong());
            }
            optionIds.add(perQuestion);
        }
        return new Survey(view.get("surveyId").asLong(), boothId, ownerId, questionIds, optionIds);
    }

    private long answerCount(Long surveyId) {
        return jdbc.queryForObject("SELECT count(*) FROM survey_answers a "
                + "JOIN survey_responses r ON r.id = a.response_id WHERE r.survey_id = ?",
                Long.class, surveyId);
    }

    private long selectedOptionCount(Long surveyId) {
        return jdbc.queryForObject("SELECT count(*) FROM survey_answer_options ao "
                + "JOIN survey_answers a ON a.id = ao.answer_id "
                + "JOIN survey_responses r ON r.id = a.response_id WHERE r.survey_id = ?",
                Long.class, surveyId);
    }

    private long responseCount(Long surveyId) {
        return jdbc.queryForObject("SELECT count(*) FROM survey_responses WHERE survey_id = ?",
                Long.class, surveyId);
    }

    private int balanceOf(Long userId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?",
                Integer.class, userId);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private String guestBearer() {
        return "Bearer " + accessTokens.issueGuestToken().token();
    }

    /** 저장된 설문의 실제 식별자들 — 문항·선택지 id 를 테스트가 알아야 답을 만들 수 있다. */
    private record Survey(Long id, Long boothId, Long ownerId, List<Long> questionIds,
                          List<List<Long>> optionIds) {

        long q(int index) {
            return questionIds.get(index);
        }

        long o(int question, int option) {
            return optionIds.get(question).get(option);
        }

        /** `Q0`·`O10` 같은 자리표시자를 실제 id 로 바꾼다 — CsvSource 가 값을 못 담기 때문이다. */
        String fill(String template) {
            String filled = template;
            for (int i = questionIds.size() - 1; i >= 0; i--) {
                filled = filled.replace("Q" + i, String.valueOf(q(i)));
                for (int j = optionIds.get(i).size() - 1; j >= 0; j--) {
                    filled = filled.replace("O" + i + j, String.valueOf(o(i, j)));
                }
            }
            return filled;
        }
    }
}
