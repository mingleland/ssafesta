package com.example.ssafesta.survey;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.publishLayout;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 동시 제출에서 응답과 보상이 각각 한 번인지 (spec 010 FR-005, 헌법 20조).
 *
 * <p>이 파일이 없으면 두 방어가 테스트를 지나지 않는다. 사전 중복 검사가 순차 요청을 전부 잡아
 * 버려서, <b>부분 유니크 인덱스의 제약 번역</b>과 <b>원장 멱등키</b>는 경합이 실제로 일어나야만
 * 실행되는 경로다. 지급은 돈이 움직이는 자리라 두 번째 방어가 있다는 것만으로는 부족하고 그것이
 * 무는 것을 봐야 한다.
 *
 * <p>MockMvc 가 아니라 서비스를 직접 부른다 — 스레드마다 요청을 만들어도 결국 같은 메서드에서
 * 만나고, 이렇게 하면 예외 종류를 그대로 볼 수 있다. 반복 실행은 `WalletConcurrencyIntegrationTest`
 * 와 같은 이유다: 한 번 통과한 경합은 닫혔다는 증명이 아니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SurveyResponseConcurrencyIntegrationTest {

    private static final int REPEATS = 3;
    private static final int REWARD_COIN = 5;

    private static final String ONE_QUESTION = """
            {"title":"동시제출","rewardCoin":5,
             "questions":[{"type":"RATING","prompt":"별점","required":true,
                           "scale":{"min":1,"max":5}}]}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private SurveyResponseService submissions;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    /**
     * 같은 회원이 동시에 네 번 제출한다. 응답은 하나, 지급도 하나여야 한다.
     *
     * <p>사전 검사를 통과한 둘 이상이 동시에 쓰기로 들어가면
     * {@code ux_survey_responses_member} 가 결정하고, 그 위반을 번역하지 않으면 사용자에게 500 이
     * 간다. 번역을 지우면 이 테스트가 그 자리에서 깨진다.
     */
    @RepeatedTest(REPEATS)
    void fourSimultaneousSubmissionsStoreOneResponseAndPayOnce() throws Exception {
        Fixture fixture = fixture("동시제출");
        int before = balanceOf(fixture.ownerId());

        List<Outcome> outcomes = runTogether(4, index -> () -> {
            try {
                submissions.submit(fixture.surveyId(),
                        SurveyResponseService.Respondent.member(fixture.ownerId()),
                        answer(fixture.questionId()));
                return Outcome.SUCCESS;
            } catch (ApiException exception) {
                return exception.errorCode() == ErrorCode.SURVEY_ALREADY_RESPONDED
                        ? Outcome.DUPLICATE
                        : Outcome.OTHER;
            }
        });

        assertEquals(1, count(outcomes, Outcome.SUCCESS), "제출은 정확히 한 건만 성공해야 합니다.");
        assertEquals(3, count(outcomes, Outcome.DUPLICATE),
                "나머지는 SURVEY_ALREADY_RESPONDED 로 거부돼야 합니다 — 다른 오류면 제약 위반이 "
                        + "번역되지 않고 그대로 새어 나온 것입니다.");
        assertEquals(1, responseCount(fixture.surveyId()), "응답 행은 하나여야 합니다.");
        assertEquals(before + REWARD_COIN, balanceOf(fixture.ownerId()), "지급은 한 번이어야 합니다.");
        assertEquals(1, rewardEntryCount(fixture.ownerId()), "원장 항목도 하나여야 합니다.");
        assertBalanceMatchesLedger(fixture.ownerId());
    }

    /**
     * 멱등키가 같은 행위에 대해 늘 같아야 한다 — 시각이나 난수가 섞이면 재시도가 두 번째 지급으로
     * 기록된다 (spec 003 FR-007).
     *
     * <p>API 로는 사전 검사와 유니크 인덱스가 먼저 막아 이 값이 드러나지 않으므로 키 자체를 본다.
     */
    @Test
    void theRewardKeyIsStableForTheSameMemberAndSurvey() {
        String first = SurveyResponseService.rewardKey(12L, 7L);
        String second = SurveyResponseService.rewardKey(12L, 7L);

        assertEquals(first, second, "같은 회원·같은 설문의 멱등키는 항상 같아야 합니다.");
        assertEquals(CoinReason.SURVEY_REWARD + ":12:7", first);
        org.junit.jupiter.api.Assertions.assertNotEquals(first,
                SurveyResponseService.rewardKey(12L, 8L), "회원이 다르면 키도 달라야 합니다.");
        org.junit.jupiter.api.Assertions.assertNotEquals(first,
                SurveyResponseService.rewardKey(13L, 7L), "설문이 다르면 키도 달라야 합니다.");
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private SurveyResponseService.SubmitCommand answer(Long questionId) {
        return new SurveyResponseService.SubmitCommand(List.of(
                new SurveyResponseService.AnswerCommand(questionId, List.of(), 4, null)));
    }

    private Fixture fixture(String prefix) throws Exception {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(ownerId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        String bearer = "Bearer " + sessions.issue(ownerId).accessToken();
        publishLayout(mockMvc, boothId, bearer);
        mockMvc.perform(put("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_QUESTION))
                .andExpect(status().isOk());

        var view = jsonMapper.readTree(mockMvc.perform(get("/api/v1/booths/{id}/survey", boothId)
                        .header("Authorization", bearer))
                .andReturn().getResponse().getContentAsString());
        return new Fixture(view.get("surveyId").asLong(),
                view.get("questions").get(0).get("questionId").asLong(), ownerId);
    }

    private List<Outcome> runTogether(int threads, TaskFactory factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                Callable<Outcome> task = factory.create(index);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(List<Outcome> outcomes, Outcome expected) {
        return outcomes.stream().filter(outcome -> outcome == expected).count();
    }

    private int responseCount(Long surveyId) {
        return jdbc.queryForObject("SELECT count(*) FROM survey_responses WHERE survey_id = ?",
                Integer.class, surveyId);
    }

    private int rewardEntryCount(Long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM coin_ledger_entries e "
                        + "JOIN wallets w ON w.id = e.wallet_id "
                        + "WHERE w.user_id = ? AND e.reason_type = 'SURVEY_REWARD'",
                Integer.class, userId);
    }

    private int balanceOf(Long userId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?",
                Integer.class, userId);
    }

    private void assertBalanceMatchesLedger(Long userId) {
        var wallet = wallets.requireWallet(userId);
        assertEquals((long) wallet.getBalance(), wallets.ledgerSumOf(wallet.getId()),
                "잔액과 원장 합계가 일치해야 합니다 (spec 003 I-1) — userId=" + userId);
    }

    private record Fixture(Long surveyId, Long questionId, Long ownerId) { }

    private enum Outcome { SUCCESS, DUPLICATE, OTHER }

    private interface TaskFactory {
        Callable<Outcome> create(int index);
    }
}
