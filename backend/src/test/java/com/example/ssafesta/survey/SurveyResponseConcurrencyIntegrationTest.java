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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private BoothRepository boothsForLock;
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


    /**
     * 편집이 보상을 올리는 중에 들어온 제출은 <b>올린 값</b>으로 지급받아야 한다.
     *
     * <p>제출이 설문을 부스 락보다 먼저 읽으면, 락이 막고 있는 바로 그 편집보다 오래된 값을 들고
     * 통과한다 — 락을 잡은 의미가 없어지고 지급액이 틀린다. 편집자 스레드가 락을 쥔 채 보상을
     * 5에서 10으로 올린 뒤 커밋하고, 그동안 제출은 락 앞에서 기다린다. 기다렸다 통과한 제출이
     * 5를 지급하면 그것이 이 결함이다.
     *
     * <p>래치로 순서를 고정한다 — 편집자가 락을 잡고 값을 바꾼 뒤에야 제출이 출발하므로, 제출이
     * 먼저 공유 락을 가져가 편집자를 막는 반대 경합은 생기지 않는다.
     */
    @Test
    void aSubmissionWaitingOnTheLockIsPaidTheEditedReward() throws Exception {
        Fixture fixture = fixture("보상변경");
        int before = balanceOf(fixture.ownerId());
        CountDownLatch editorHoldsLock = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> editor = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                boothsForLock.findWithLockById(fixture.boothId()).orElseThrow();
                jdbc.update("UPDATE surveys SET reward_coin = ? WHERE id = ?", 10, fixture.surveyId());
                editorHoldsLock.countDown();
                sleepQuietly(400);
                return null;
            }));

            Future<Integer> submitter = pool.submit(() -> {
                editorHoldsLock.await();
                return submissions.submit(fixture.surveyId(),
                        SurveyResponseService.Respondent.member(fixture.ownerId()),
                        answer(fixture.questionId())).rewardedCoin();
            });

            editor.get(30, TimeUnit.SECONDS);
            assertEquals(10, submitter.get(30, TimeUnit.SECONDS),
                    "락을 기다렸다 통과한 제출은 편집이 커밋한 보상액을 지급해야 합니다 — "
                            + "5가 나오면 설문을 락보다 먼저 읽은 것입니다.");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(before + 10, balanceOf(fixture.ownerId()), "지갑에도 올린 값이 들어가야 합니다.");
        assertBalanceMatchesLedger(fixture.ownerId());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * 임대와 제출이 겹쳐도 교착이 나지 않아야 한다 — 두 트랜잭션이 지갑과 부스를 같은 순서로
     * 잡을 때만 성립한다.
     *
     * <p>{@code BoothLeaseService.lease} 와 {@code InventoryService} 는 무엇을 하기도 전에
     * {@code wallets.lockOwner} 를 부르고, 임대는 그 뒤에 부스 행을 쓴다. 즉 저장소의 순서는
     * <b>지갑 → 부스</b>다. 제출이 부스를 먼저 잡으면 사이클이 닫힌다: 임대는 지갑을 쥔 채 부스를
     * 기다리고, 제출은 그 부스를 쥔 채 같은 지갑을 기다린다. PostgreSQL 이 둘 중 하나를 죽인다.
     *
     * <p>여기서는 임대 트랜잭션을 손으로 흉내 낸다 — 지갑 락을 잡고, 래치를 열어 제출을 출발시킨
     * 뒤, 부스 행을 쓴다. 제출이 부스를 먼저 잡던 시절에는 이 지점에서 교착이 났다.
     */
    @RepeatedTest(REPEATS)
    void aLeaseAndASubmissionDoNotDeadlock() throws Exception {
        Fixture fixture = fixture("교착");
        CountDownLatch walletLocked = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> lease = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                wallets.lockOwner(fixture.ownerId());
                walletLocked.countDown();
                sleepQuietly(300);
                // 임대가 booth.attachSlot 으로 하는 것과 같은 것 — 부스 행에 배타 락이 걸린다.
                jdbc.update("UPDATE booths SET name = name WHERE id = ?", fixture.boothId());
                return null;
            }));

            Future<Integer> submitter = pool.submit(() -> {
                walletLocked.await();
                return submissions.submit(fixture.surveyId(),
                        SurveyResponseService.Respondent.member(fixture.ownerId()),
                        answer(fixture.questionId())).rewardedCoin();
            });

            lease.get(30, TimeUnit.SECONDS);
            assertEquals(REWARD_COIN, submitter.get(30, TimeUnit.SECONDS),
                    "제출은 교착 없이 끝나고 보상을 지급해야 합니다.");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, responseCount(fixture.surveyId()));
        assertBalanceMatchesLedger(fixture.ownerId());
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
                view.get("questions").get(0).get("questionId").asLong(), ownerId, boothId);
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

    private record Fixture(Long surveyId, Long questionId, Long ownerId, Long boothId) { }

    private enum Outcome { SUCCESS, DUPLICATE, OTHER }

    private interface TaskFactory {
        Callable<Outcome> create(int index);
    }
}
