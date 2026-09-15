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
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 부스에 속하지 않는 이벤트 설문 (S15P21A604-621, GitLab #173 · 계약 §8-1).
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
    @Autowired private SurveyResponseService submissions;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 시드 설문 하나를 스위트 전체가 공유하므로 매번 비운다.
     *
     * <p>응답을 지우는 것은 1인 1응답 때문이고(앞 테스트의 응답이 남으면 다음 테스트가 처음부터
     * "이미 참여함" 을 본다), <b>문항까지 지우는 것은 개수를 세는 단정 때문</b>이다. 남겨 두면
     * 실행 순서에 따라 문항 수가 달라져 통과 여부가 운에 걸린다.
     */
    @BeforeEach
    void freeSlotsAndResetTheSeededSurvey() {
        releaseAllSlots(jdbc);
        Long surveyId = eventSurveyId();
        jdbc.update("DELETE FROM survey_answer_options WHERE answer_id IN (SELECT sa.id FROM survey_answers sa"
                + " JOIN survey_responses sr ON sr.id = sa.response_id WHERE sr.survey_id = ?)", surveyId);
        jdbc.update("DELETE FROM survey_answers WHERE response_id IN"
                + " (SELECT id FROM survey_responses WHERE survey_id = ?)", surveyId);
        jdbc.update("DELETE FROM survey_responses WHERE survey_id = ?", surveyId);
        jdbc.update("DELETE FROM survey_options WHERE question_id IN"
                + " (SELECT id FROM survey_questions WHERE survey_id = ?)", surveyId);
        jdbc.update("DELETE FROM survey_questions WHERE survey_id = ?", surveyId);
        jdbc.update("UPDATE surveys SET ends_at = NULL WHERE id = ?", surveyId);
    }

    // ── 아직 공개되지 않은 설문 ─────────────────────────────────────────────

    /**
     * 문항이 없는 이벤트 설문은 조회도 제출도 막는다 (S15P21A604-621).
     *
     * <p>시드는 설문 행부터 넣고 문항은 기획 문구가 도착한 뒤 얹는다. 그 사이를 열어 두면
     * <b>1인 1응답이 사용자에게 불리하게 소진된다</b> — 답할 것이 없는 화면에서 제출한 빈 응답이
     * 저장되고, 문항이 생긴 뒤 그 회원은 영영 참여할 수 없다. 추첨 명단에는 아무것도 답하지 않은
     * 사람이 남는다.
     *
     * <p>조회와 제출을 한 테스트에 둔 것은 <b>한쪽만 막으면 다른 쪽이 뚫리기</b> 때문이다.
     */
    @Test
    void aQuestionlessEventSurveyIsNeitherReadableNorAnswerable() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "미공개");

        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", bearerFor(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));

        mockMvc.perform(submitEmpty(bearerFor(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));

        assertEquals(0, countResponses(), "빈 응답이 저장되면 그 회원의 참여 기회가 사라진다");
    }

    /**
     * 문항이 없으면서 마감이기도 하면 <b>문항 없음이 이긴다</b> — 조회와 제출이 같은 답을 한다.
     *
     * <p>겹치지 않는 상태만 보면 판정 순서를 바꿔도 전부 통과한다. 실제로 제출이 마감 검사를 먼저
     * 하고 있어서, 같은 설문이 조회에서는 404 인데 제출에서는 409 였다. 아직 공개되지 않은 것은
     * 마감될 수도 없다.
     */
    @Test
    void beingUnpublishedOutranksBeingClosed() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "미공개마감");
        jdbc.update("UPDATE surveys SET ends_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), eventSurveyId());

        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", bearerFor(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));

        mockMvc.perform(submitEmpty(bearerFor(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
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
        seedQuestion("남의 참여 문항");
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

    /** 답이 응답 행으로만 남고 사라지면 추첨은 몰라도 집계가 빈다 — 실제 답변까지 저장되는지 본다. */
    @Test
    void theAnswersThemselvesAreStoredNotJustTheResponseRow() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "답변저장");
        long questionId = seedQuestion("가장 좋았던 부스는?");

        mockMvc.perform(submitBody(bearerFor(memberId),
                        "{\"answers\":[{\"questionId\":%d,\"text\":\"7번 부스\"}]}".formatted(questionId)))
                .andExpect(status().isCreated());

        assertEquals(1, countResponses());
        assertEquals("7번 부스", jdbc.queryForObject(
                "SELECT sa.text_answer FROM survey_answers sa JOIN survey_responses sr ON sr.id = sa.response_id"
                        + " WHERE sr.survey_id = ? AND sa.question_id = ?",
                String.class, eventSurveyId(), questionId));
    }

    /** 필수 문항은 이벤트 경로에서도 필수다 — 부스 설문과 같은 검증을 그대로 탄다. */
    @Test
    void aRequiredQuestionMustBeAnswered() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "필수문항");
        seedRequiredQuestion("반드시 답할 문항");

        mockMvc.perform(submitEmpty(bearerFor(memberId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0, countResponses());
    }

    /** 마감은 이벤트 설문도 같다. 행사가 끝나면 ends_at 하나로 닫는다. */
    @Test
    void aClosedEventSurveyRefusesSubmission() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "마감이벤트");
        seedQuestion("마감 문항");
        jdbc.update("UPDATE surveys SET ends_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), eventSurveyId());

        mockMvc.perform(submitEmpty(bearerFor(memberId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SURVEY_CLOSED"));

        // 마감돼도 조회는 열린다 — 무엇을 물었는지는 남는다 (부스 설문과 같은 규칙).
        mockMvc.perform(get(runPath(EVENT_KEY)).header("Authorization", bearerFor(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        assertEquals(0, countResponses());
    }

    /**
     * 같은 회원이 동시에 제출해도 응답은 하나다.
     *
     * <p><b>이 테스트가 무엇을 증명하고 무엇을 증명하지 않는지</b>를 적어 둔다. 회원 제출은
     * {@code WalletService.lockOwner} 가 지갑 행에 쓰기 락을 잡아 <b>같은 회원끼리 직렬화된다</b> —
     * 이벤트 설문에 부스 공유 락이 없어도 그렇다. 그래서 뒤따르는 요청은 사전 {@code alreadyResponded}
     * 검사에서 끝나고, {@code ux_survey_responses_member} 위반과 그 번역은 여기서 일어나지 않는다.
     *
     * <p>즉 이 테스트가 고정하는 것은 <b>결과</b>(4건 중 1건만 저장)이지 제약 번역 경로가 아니다.
     * 그 경로는 지갑 락이 없는 게스트에서만 실제로 밟히므로 {@code SurveyResponseConcurrencyIntegrationTest}
     * 가 따로 본다.
     */
    @Test
    void simultaneousSubmissionsFromOneMemberStoreExactlyOneResponse() throws Exception {
        Long memberId = createMemberWithWallet(users, wallets, "동시이벤트");
        long questionId = seedQuestion("동시 문항");
        SurveyResponseService.SubmitCommand command = new SurveyResponseService.SubmitCommand(
                List.of(new SurveyResponseService.AnswerCommand(questionId, List.of(), null, "동시")));

        int writers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        submissions.submit(eventSurveyId(),
                                SurveyResponseService.Respondent.member(memberId), command);
                        return "SUCCESS";
                    } catch (ApiException exception) {
                        return exception.errorCode().name();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, outcomes.stream().filter("SUCCESS"::equals).count(),
                    "정확히 하나만 성공해야 합니다: " + outcomes);
            assertEquals(writers - 1, outcomes.stream().filter("SURVEY_ALREADY_RESPONDED"::equals).count(),
                    "나머지는 제약 위반이 번역된 결과여야 합니다 — 다른 값이면 그대로 새어 나온 것입니다: "
                            + outcomes);
            assertEquals(1, countResponses());
        } finally {
            pool.shutdownNow();
        }
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
        return seedQuestion(prompt, false);
    }

    private long seedRequiredQuestion(String prompt) {
        return seedQuestion(prompt, true);
    }

    /** 시드 경로가 없으므로 직접 넣는다 — 이벤트 설문에는 편집 endpoint 가 없다. */
    private long seedQuestion(String prompt, boolean required) {
        jdbc.update("INSERT INTO survey_questions (survey_id, question_type, question_text,"
                        + " is_required, display_order) VALUES (?, 'SHORT_TEXT', ?, ?, ?)",
                eventSurveyId(), prompt, required, nextDisplayOrder());
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
        return submitBody(bearer, "{\"answers\":[]}");
    }

    private org.springframework.test.web.servlet.RequestBuilder submitBody(String bearer, String body) {
        return post("/api/v1/surveys/{id}/responses", eventSurveyId())
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
