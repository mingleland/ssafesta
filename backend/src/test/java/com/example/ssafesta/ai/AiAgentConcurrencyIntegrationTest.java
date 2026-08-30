package com.example.ssafesta.ai;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothLayoutService;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.LayoutValidationFailedException;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 부스당 1명(C-13)과 "공개 배치는 존재하는 직원만 가리킨다"(A-3)가 <b>경쟁 조건에서도</b>
 * 지켜지는지 (spec 007 C-14).
 *
 * <p>등록 경쟁은 {@code ux_ai_agents_booth}(V15)가 막는다. 삭제↔공개 경쟁을 막는 것은 잠금 하나뿐이다
 * — 배치의 직원 참조는 JSON 안이라 외래키가 없고, 진 쪽을 잡아 줄 최후 방어가 DB 에 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiAgentConcurrencyIntegrationTest {

    private static final String MINIMAL = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";
    private static final int TIMEOUT_SECONDS = 30;

    @Autowired private AiAgentService agentService;
    @Autowired private AiAgentRepository agents;
    @Autowired private BoothLayoutService layoutService;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    /**
     * 사전 조회 검사만으로는 못 막는다 — 두 요청이 각각 "없음"을 읽고 각각 INSERT 한다.
     *
     * <p>한 번 통과한 경쟁은 닫혔다는 증거가 못 되므로 반복한다
     * ({@code ProjectConcurrencyIntegrationTest} 와 같은 이유).
     */
    @RepeatedTest(5)
    void twoRequestsRacingToRegisterProduceExactlyOneAgent() throws Exception {
        Owner owner = leasedOwner("등록경합");

        List<Outcome> outcomes = registerTogether(owner);

        long created = outcomes.stream().filter(Outcome::succeeded).count();
        assertEquals(1, created, "정확히 하나만 만들어져야 한다");
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                // 500 이 아니라 409 다. save() 만 감쌌다면 유니크 위반이 커밋 시점에 터진다.
                assertInstanceOf(AiAgentLimitException.class, outcome.failure(),
                        "경쟁에서 진 쪽도 AGENT_LIMIT_EXCEEDED 여야 한다"));
        assertEquals(1, agents.findAll().stream()
                        .filter(agent -> agent.getBoothId().equals(owner.boothId())).count(),
                "행도 하나여야 한다");
    }

    /**
     * ⓐ 공개가 먼저 잠금을 잡으면, 삭제는 <b>기다렸다가</b> 공개된 참조를 보고 거절한다.
     *
     * <p>시작 상태는 직원을 안 거는 Draft 다 — 그래야 삭제의 Draft 검사가 통과하고 경쟁이 실제로
     * 열린다. 공개 쪽이 그 사이에 직원을 건 Draft 를 저장하고 공개한다.
     *
     * <p>삭제 스레드가 잠금 앞에 도달했는지까지는 관측할 수 없다 — 그런 신호가 없다. 그래서 이
     * 테스트가 고정하는 것은 인터리빙이 아니라 <b>결과</b>다: 어느 쪽으로 갈리든 삭제는 409 여야
     * 하고 공개된 배치는 살아 있는 직원을 가리켜야 한다.
     */
    @Test
    void publishFirstMakesTheDeleteLoseAndSayWhy() throws Exception {
        Owner owner = leasedOwner("공개선점");
        long agentId = createAgent(owner);
        saveDraftWithoutAgent(owner);

        CountDownLatch publishHoldsTheLock = new CountDownLatch(1);
        CountDownLatch deleteAttempted = new CountDownLatch(1);

        Outcome delete = whileHoldingTransaction(
                () -> {
                    saveDraftReferencing(owner, agentId);
                    layoutService.publish(owner.boothId(), owner.userId());
                    publishHoldsTheLock.countDown();
                    await(deleteAttempted);
                },
                () -> {
                    await(publishHoldsTheLock);
                    deleteAttempted.countDown();
                    agentService.delete(agentId, owner.userId());
                });

        assertInstanceOf(AiAgentDeleteConflictException.class, delete.failure(),
                "공개된 배치가 가리키는 직원은 삭제될 수 없다");
        assertTrue(delete.failure().getMessage().contains("배치"), "무엇이 막는지 말해야 한다");
        assertTrue(agents.findById(agentId).isPresent(), "직원은 살아 있어야 한다");
        assertEquals(1, publishedVersionOf(owner.boothId()), "공개는 성공했어야 한다");
    }

    /**
     * ⓑ 삭제가 먼저면 공개가 거절된다 — 사라진 직원을 가리킨 채 공개되지 않는다.
     *
     * <p>이 방향의 방어는 {@code LayoutValidator} 의 {@code CONFIG_NOT_OWNED} 다. 삭제가 커밋된
     * <b>뒤에</b> 공개가 배치를 다시 검증해야 성립하고, 그 "뒤"를 만드는 것이 잠금이다.
     */
    @Test
    void deleteFirstMakesThePublishRefuseTheGhost() throws Exception {
        Owner owner = leasedOwner("삭제선점");
        long agentId = createAgent(owner);
        // 참조 없는 배치를 먼저 공개해 둔다 — 삭제가 공개 배치 검사에 걸리지 않아야 경쟁이 열린다.
        saveDraftWithoutAgent(owner);
        layoutService.publish(owner.boothId(), owner.userId());

        CountDownLatch deleteHoldsTheLock = new CountDownLatch(1);
        CountDownLatch publishAttempted = new CountDownLatch(1);

        Outcome publish = whileHoldingTransaction(
                () -> {
                    agentService.delete(agentId, owner.userId());
                    // 삭제한 뒤에야 직원을 거는 Draft 를 저장한다. 순서가 반대면 삭제가 Draft
                    // 참조에 걸려 애초에 경쟁이 열리지 않는다.
                    saveDraftReferencing(owner, agentId);
                    deleteHoldsTheLock.countDown();
                    await(publishAttempted);
                },
                () -> {
                    await(deleteHoldsTheLock);
                    publishAttempted.countDown();
                    layoutService.publish(owner.boothId(), owner.userId());
                });

        LayoutValidationFailedException refusal = assertInstanceOf(
                LayoutValidationFailedException.class, publish.failure(),
                "사라진 직원을 가리키는 배치는 공개될 수 없다");
        assertTrue(refusal.errors().stream()
                        .anyMatch(error -> "CONFIG_NOT_OWNED".equals(error.rule())),
                "거절 이유는 연결 대상이 이 부스 것이 아니라는 것이어야 한다");
        assertTrue(agents.findById(agentId).isEmpty(), "삭제는 성공했어야 한다");
        assertEquals(1, publishedVersionOf(owner.boothId()), "공개 버전은 그대로여야 한다");
    }

    // ── 실행 도구 ────────────────────────────────────────────────────────────

    /**
     * 첫 작업을 트랜잭션 안에 <b>열어 둔 채</b> 두 번째를 다른 스레드에서 부른다.
     *
     * @return 두 번째 작업의 결과
     */
    private Outcome whileHoldingTransaction(Runnable holder, Runnable contender) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> contenderResult = pool.submit(() -> run(contender));
            Future<Outcome> holderResult = pool.submit(() -> run(
                    () -> new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> holder.run())));
            // 시한을 건다. 인자 없는 get() 은 잠금이 안 풀리면 스위트 전체를 세운다.
            Outcome held = holderResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!held.succeeded()) {
                throw new IllegalStateException(
                        "선점 작업이 실패했다 — 경쟁을 재현하지 못했다", held.failure());
            }
            return contenderResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Outcome> registerTogether(Owner owner) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> run(() -> {
                    await(start);
                    agentService.create(owner.boothId(), owner.userId(), command(MINIMAL));
                })));
            }
            start.countDown(); // 둘을 같이 놓아야 실제로 겹친다
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome run(Runnable action) {
        try {
            action.run();
            return new Outcome(true, null);
        } catch (RuntimeException exception) {
            return new Outcome(false, exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("상대 스레드를 기다리다 시한이 지났다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private long createAgent(Owner owner) {
        return agentService.create(owner.boothId(), owner.userId(), command(MINIMAL)).agentId();
    }

    private void saveDraftReferencing(Owner owner, long agentId) {
        layoutService.saveDraft(owner.boothId(), owner.userId(), draftBody(owner.boothId(), """
                [{"objectId":"ai-1","type":"AI_AGENT","position":{"x":0.0,"y":0.0,"z":0.0},
                  "rotationY":0.0,"configId":%d}]""".formatted(agentId)));
    }

    private void saveDraftWithoutAgent(Owner owner) {
        layoutService.saveDraft(owner.boothId(), owner.userId(), draftBody(owner.boothId(), """
                [{"objectId":"deco-1","type":"DECORATION","position":{"x":0.0,"y":0.0,"z":0.0},
                  "rotationY":0.0}]"""));
    }

    private String draftBody(Long boothId, String objectsJson) {
        Long revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM booth_layout_drafts WHERE booth_id = ?",
                Long.class, boothId);
        return """
                {"expectedRevision":%d,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":%s}"""
                .formatted(revision == null ? 0L : revision, objectsJson);
    }

    private int publishedVersionOf(Long boothId) {
        Integer version = jdbc.queryForObject(
                "SELECT published_layout_version FROM booths WHERE id = ?", Integer.class, boothId);
        return version == null ? 0 : version;
    }

    /** presence 추적 클래스라 setter 로만 채울 수 있다 — Jackson 을 그대로 쓴다. */
    private AiAgentService.AgentCommand command(String json) {
        return jsonMapper.readValue(json, AiAgentService.AgentCommand.class);
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }

    private record Outcome(boolean succeeded, RuntimeException failure) { }
}
