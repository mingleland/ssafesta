package com.example.ssafesta.internal.storage;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/storage/reconciliation-runs} — Infra 가 실행한 reconcile 결과를 Spring 이
 * 받는 경로 (spec 007 FR-035 · T077, S15P21A604-500).
 *
 * <p>고정하는 것은 두 가지다. 하나는 <b>늦게 도착한 결과가 최신 저장 위치를 되돌리지 않는다</b>는
 * 것 — 반영 조건에서 source provider 비교가 빠지면 이 경로가 조용히 문서를 과거 위치로 되돌린다.
 * 다른 하나는 <b>재전송이 최초 응답과 같은 뜻</b>이라는 것 — 재전송을 무조건 204 로 만들면 같은
 * 요청이 1회차 409, 2회차 204 가 된다.
 *
 * <p>두 provider 를 모두 구성한 컨텍스트다. 기본 테스트 설정은 R2 만 두므로(=R2 만 쓰는 배포가
 * fallback 자격증명 없이 뜨는 것이 계약이다) provider 미구성 쪽은
 * {@link StorageReconciliationUnconfiguredProviderIntegrationTest} 가 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.ai.storage.providers.MINIO_LOCAL.endpoint=http://localhost:9",
        "app.ai.storage.providers.MINIO_LOCAL.bucket=test-fallback",
        "app.ai.storage.providers.MINIO_LOCAL.access-key-id=test-access-key",
        "app.ai.storage.providers.MINIO_LOCAL.secret-access-key=test-secret-key"})
@AutoConfigureMockMvc
class StorageReconciliationControllerIntegrationTest {

    /** {@code src/test/resources/application-local.properties} 가 넣는 값과 같아야 한다. */
    private static final String INFRA_TOKEN = "test-infra-to-spring";

    /** 같은 파일의 AI 방향 값. 이 경로에서는 아무것도 열지 못해야 한다. */
    private static final String AI_TOKEN = "test-ai-to-spring";

    private static final String R2_BUCKET = "test-ai-documents";
    private static final String MINIO_BUCKET = "test-fallback";

    private static final String SHA = "%064x".formatted(11);
    private static final String OTHER_SHA = "%064x".formatted(12);
    private static final String CHECKED_AT = "2026-09-10T03:00:00Z";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @MockitoSpyBean private StorageReconciliationRepository results;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 반영 판정 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VERIFIED 는 문서의 provider 와 bucket 을 함께 옮긴다")
    void aVerifiedResultMovesProviderAndBucketTogether() throws Exception {
        Document document = seed("이동");

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL")))
                .andExpect(status().isNoContent());

        assertEquals("MINIO_LOCAL", providerOf(document));
        // bucket 이 따라오지 않으면 이후 읽기·삭제가 없는 좌표를 친다 — 두 provider 의 버킷
        // 이름이 다르기 때문이다.
        assertEquals(MINIO_BUCKET, bucketOf(document));
        assertEquals("APPLIED", applyResultOf(document));
    }

    /**
     * 늦게 도착한 결과가 최신 위치를 되돌리지 않는다.
     *
     * <p>이 단정이 없으면 반영 조건에서 source provider 비교를 지워도 아무 테스트가 붉어지지
     * 않는다. 그 상태에서 이 경로는 문서를 과거 위치로 되돌리는 쓰기가 된다.
     */
    @Test
    @DisplayName("source 가 문서의 현재 provider 와 다르면 STALE 이고 문서는 그대로다")
    void aResultWhoseSourceIsNotTheCurrentProviderIsStale() throws Exception {
        Document document = seed("떠난문서");
        moveTo(document, "MINIO_LOCAL", MINIO_BUCKET);

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_STALE"));

        assertEquals("MINIO_LOCAL", providerOf(document));
        assertEquals(MINIO_BUCKET, bucketOf(document));
        // 반영은 못 했어도 결과는 남는다 — 그것이 이 409 가 가리키는 증거다.
        assertEquals("STALE", applyResultOf(document));
    }

    @Test
    @DisplayName("R2→MINIO 반영 뒤 늦게 온 R2 발 결과는 문서를 되돌리지 않는다")
    void aLateResultDoesNotRollBackTheNewerLocation() throws Exception {
        Document document = seed("롤백방지");

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL")))
                .andExpect(status().isNoContent());
        mockMvc.perform(submit(verified(document, "R2", "R2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_STALE"));

        assertEquals("MINIO_LOCAL", providerOf(document));
        assertEquals(MINIO_BUCKET, bucketOf(document));
    }

    /** 만료 원본 삭제 스윕이 {@code s3_key} 를 비운 문서에는 반영할 것이 없다 (FR-028). */
    @Test
    @DisplayName("문서가 그 object key 를 더는 갖지 않으면 STALE 이다")
    void aResultForAnObjectKeyTheDocumentNoLongerHasIsStale() throws Exception {
        Document document = seed("키없음");
        jdbc.update("UPDATE ai_documents SET s3_key = NULL WHERE id = ?", document.id());

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_STALE"));

        assertEquals("R2", providerOf(document));
        assertEquals("STALE", applyResultOf(document));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISMATCH", "MISSING"})
    @DisplayName("VERIFIED 가 아닌 결과는 적재만 하고 문서를 건드리지 않는다")
    void aNonVerifiedResultIsOnlyRecorded(String status) throws Exception {
        Document document = seed("기록만" + status);
        Map<String, Object> payload = payload(document, "R2", "MINIO_LOCAL", status);
        payload.put("failureReason", "size mismatch");

        mockMvc.perform(submit(payload)).andExpect(status().isNoContent());

        assertEquals("R2", providerOf(document));
        assertEquals(R2_BUCKET, bucketOf(document));
        assertEquals("LOGGED_ONLY", applyResultOf(document));
    }

    /** {@code R2_RECONCILING} 의 제자리 검증이 이 모양이다 — 계약이 허용한다. */
    @Test
    @DisplayName("source 와 target 이 같아도 받는다")
    void anInPlaceVerificationIsAccepted() throws Exception {
        Document document = seed("제자리");

        mockMvc.perform(submit(verified(document, "R2", "R2")))
                .andExpect(status().isNoContent());

        assertEquals("R2", providerOf(document));
        assertEquals(R2_BUCKET, bucketOf(document));
        assertEquals("APPLIED", applyResultOf(document));
    }

    // ── 재전송 멱등 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("반영된 결과를 다시 보내도 204 이고 문서는 한 번만 바뀐다")
    void anAppliedResultResentAnswersTheSameWay() throws Exception {
        Document document = seed("재전송반영");
        Map<String, Object> payload = verified(document, "R2", "MINIO_LOCAL");

        mockMvc.perform(submit(payload)).andExpect(status().isNoContent());
        mockMvc.perform(submit(payload)).andExpect(status().isNoContent());

        assertEquals(1, logCount(document));
        assertEquals("MINIO_LOCAL", providerOf(document));
        assertEquals(MINIO_BUCKET, bucketOf(document));
    }

    /**
     * 같은 요청이 두 번째에 다른 뜻이 되지 않는다.
     *
     * <p>재전송을 무조건 204 로 만들면 같은 payload 가 1회차 409, 2회차 204 가 된다. 저장된
     * {@code apply_result} 를 되살리는 것이 그것을 막는 유일한 자리다.
     */
    @Test
    @DisplayName("STALE 결과를 다시 보내도 계속 409 다")
    void aStaleResultResentStaysAConflict() throws Exception {
        Document document = seed("재전송STALE");
        moveTo(document, "MINIO_LOCAL", MINIO_BUCKET);
        Map<String, Object> payload = verified(document, "R2", "MINIO_LOCAL");

        mockMvc.perform(submit(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_STALE"));
        mockMvc.perform(submit(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_STALE"));

        assertEquals(1, logCount(document));
        assertEquals("MINIO_LOCAL", providerOf(document));
    }

    @Test
    @DisplayName("MISMATCH 결과를 다시 보내도 204 다")
    void aLoggedOnlyResultResentAnswers204() throws Exception {
        Document document = seed("재전송기록");
        Map<String, Object> payload = payload(document, "R2", "MINIO_LOCAL", "MISMATCH");

        mockMvc.perform(submit(payload)).andExpect(status().isNoContent());
        mockMvc.perform(submit(payload)).andExpect(status().isNoContent());

        assertEquals(1, logCount(document));
    }

    /**
     * 같은 키에 다른 내용은 멱등이 아니라 보내는 쪽의 버그다.
     *
     * <p>조용히 삼키면 그 버그가 감춰지고, 먼저 저장된 결과와 실제로 일어난 일이 갈라진다.
     */
    @Test
    @DisplayName("같은 runId + documentId 로 다른 내용이 오면 409 이고 먼저 저장된 결과가 남는다")
    void aResendWithDifferentContentIsRefused() throws Exception {
        Document document = seed("내용다름");
        Map<String, Object> first = payload(document, "R2", "MINIO_LOCAL", "MISMATCH");
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("attemptCount", 2);

        mockMvc.perform(submit(first)).andExpect(status().isNoContent());
        mockMvc.perform(submit(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_REPLAY_CONFLICT"));

        assertEquals(1, logCount(document));
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempt_count FROM storage_reconciliation_log WHERE document_id = ?",
                Integer.class, document.id()));
    }

    /**
     * 판정이 {@code attemptCount} 한 필드에만 걸려 있지 않다는 단정.
     *
     * <p>이것 없이는 재전송 비교에서 {@code status}·{@code targetProvider} 같은 필드를 빼도 아무
     * 테스트가 붉어지지 않는다. 여기서는 판정 자체가 {@code Payload} 의 record equality 라
     * 필드를 빠뜨릴 자리가 없지만, 그 성질이 유지되는지는 이 단정이 본다.
     */
    @Test
    @DisplayName("같은 키에 다른 status·targetProvider 가 와도 409 다")
    void aResendThatChangesAnyContractFieldIsRefused() throws Exception {
        Document document = seed("필드다름");
        Map<String, Object> first = payload(document, "R2", "MINIO_LOCAL", "MISMATCH");
        first.put("failureReason", "size mismatch");
        mockMvc.perform(submit(first)).andExpect(status().isNoContent());

        Map<String, Object> otherStatus = new LinkedHashMap<>(first);
        otherStatus.put("status", "MISSING");
        mockMvc.perform(submit(otherStatus))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_REPLAY_CONFLICT"));

        Map<String, Object> otherTarget = new LinkedHashMap<>(first);
        otherTarget.put("targetProvider", "R2");
        mockMvc.perform(submit(otherTarget))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_REPLAY_CONFLICT"));

        assertEquals(1, logCount(document));
        assertEquals("MISMATCH", jdbc.queryForObject(
                "SELECT status FROM storage_reconciliation_log WHERE document_id = ?",
                String.class, document.id()));
    }

    // ── 거부 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("없는 documentId 는 404 다")
    void anUnknownDocumentIsNotFound() throws Exception {
        Map<String, Object> payload = payload(new Document(999_999_999L, "seed/none"),
                "R2", "MINIO_LOCAL", "VERIFIED");

        mockMvc.perform(submit(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    /**
     * 계약을 벗어난 payload 는 422 다.
     *
     * <p>이 저장소에서 422 를 쓰는 유일한 경로다. 소비자가 사람이 아니라 Infra 스크립트라
     * 사용자 입력 오류(400)와 구분되는 편이 낫다는 계약의 선택이다.
     */
    @Test
    @DisplayName("계약을 벗어난 payload 는 422 다")
    void aPayloadOutsideTheContractIsRejected() throws Exception {
        Document document = seed("형식");

        assertRejected(without(document, "runId"), "runId");
        assertRejected(without(document, "checkedAt"), "checkedAt");
        assertRejected(with(document, "sourceProvider", "S3"), "sourceProvider");
        assertRejected(with(document, "status", "OK"), "status");
        assertRejected(with(document, "attemptCount", 0), "attemptCount");
        assertRejected(with(document, "expectedSize", -1), "expectedSize");
        assertRejected(with(document, "expectedSha256", "NOTAHASH"), "expectedSha256");
        assertRejected(with(document, "runId", "r".repeat(201)), "runId");
        // 공백만 있는 키는 아무도 되짚을 수 없는 멱등 키다.
        assertRejected(with(document, "runId", "   "), "runId");
        assertRejected(with(document, "objectKey", " "), "objectKey");
        assertRejected(with(document, "resolvedAt", "2026-09-10T02:59:59Z"), "resolvedAt");
    }

    @Test
    @DisplayName("계약에 없는 필드는 무시하지 않고 422 로 거절한다")
    void anUndefinedFieldIsRejected() throws Exception {
        Document document = seed("모르는필드");
        Map<String, Object> payload = verified(document, "R2", "MINIO_LOCAL");
        payload.put("targetBucket", "guessed");

        assertRejected(payload, "targetBucket");
    }

    /**
     * 스스로 어긋난 결과는 반영할 수 없다.
     *
     * <p>상태별로 어떤 필드가 <i>필수</i> 인지는 계약에 없고 여기서 만들지 않는다 — Spring 은
     * Infra 의 판정을 다시 검증하는 쪽이 아니다. 판정이 자기 근거와 어긋나는 것만 거절한다.
     */
    @Test
    @DisplayName("자기모순인 결과는 422 다")
    void aSelfContradictoryResultIsRejected() throws Exception {
        Document document = seed("자기모순");

        Map<String, Object> mismatchedHash = verified(document, "R2", "MINIO_LOCAL");
        mismatchedHash.put("expectedSha256", SHA);
        mismatchedHash.put("actualSha256", OTHER_SHA);
        assertRejected(mismatchedHash, "actualSha256");

        Map<String, Object> verifiedWithFailure = verified(document, "R2", "MINIO_LOCAL");
        verifiedWithFailure.put("failureReason", "그런데 실패했다");
        assertRejected(verifiedWithFailure, "failureReason");

        Map<String, Object> missingWithEvidence =
                payload(document, "R2", "MINIO_LOCAL", "MISSING");
        missingWithEvidence.put("actualSize", 10);
        assertRejected(missingWithEvidence, "actualSize");
    }

    /** 오류 본문은 계약 0.2.0 이 맞춘 공용 봉투다 — 다섯 키가 항상 있다. */
    @Test
    @DisplayName("오류 본문이 공용 봉투 다섯 키를 갖는다")
    void theErrorBodyIsTheSharedEnvelope() throws Exception {
        Document document = seed("봉투");

        mockMvc.perform(submit(without(document, "runId")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_INVALID"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.warnings").isArray());
    }

    // ── 인증 경계 ────────────────────────────────────────────────────────────

    @Test
    void aMissingServiceTokenIsRefused() throws Exception {
        Document document = seed("토큰없음");

        mockMvc.perform(post("/internal/storage/reconciliation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(verified(document, "R2", "MINIO_LOCAL"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aWrongServiceTokenIsRefused() throws Exception {
        Document document = seed("토큰틀림");

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL"), INFRA_TOKEN + "-tampered"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * AI 방향 토큰으로는 이 경로가 열리지 않는다 — 계약이 401 로 못박았고 Infra 의 경계 시험
     * (spec 007 T079)이 그 상태를 본다.
     *
     * <p>403 이 아니라 401 인 이유는 필터가 자기 경로 밖에서는 아무것도 인증하지 않기 때문이다.
     */
    @Test
    void theAiDirectionTokenDoesNotOpenTheStoragePath() throws Exception {
        Document document = seed("AI토큰");

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL"), AI_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * 역방향도 막힌다.
     *
     * <p>이 단정과 위의 정상 경로 204 가 함께 {@code OncePerRequestFilter} 의 함정을 지킨다 —
     * 두 필터가 scope 를 {@code shouldNotFilter} 가 아니라 본문에서 보면, 먼저 도는 쪽이 남긴
     * already-filtered 표시 때문에 뒤쪽 필터의 본문이 통째로 건너뛰어지고 유효한 토큰이 401 을
     * 받는다.
     */
    @Test
    void theInfraTokenDoesNotOpenTheAiPath() throws Exception {
        mockMvc.perform(get("/internal/ai/booth-access")
                        .header("Authorization", "Bearer " + INFRA_TOKEN)
                        .param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    // ── 트랜잭션 경계 ────────────────────────────────────────────────────────

    /**
     * 적재와 문서 갱신은 한 번의 쓰기다.
     *
     * <p>이 단정이 지키는 것은 {@code @Transactional} 이 <b>실제로 걸려 있는지</b>다. 안 걸리면 각
     * 문장이 autocommit 으로 돌아 {@code FOR UPDATE} 잠금이 즉시 풀리고 여기서 로그 행만 남는다.
     * 조용히 사라지는 종류의 실수라 동시성 테스트로는 간헐적으로만 드러난다.
     *
     * <p>가시성 규칙을 여기 적지 않는 이유는 T-147 이다 — "public 이 아닌 메서드는 건너뛴다" 는
     * 옛 규칙이 이 버전에서는 사실이 아니었고, 그 문장을 근거로 코드를 고쳤다가 되돌렸다. 이
     * 테스트는 규칙이 어느 쪽이든 <b>트랜잭션이 실제로 걸렸는지</b>만 본다.
     */
    @Test
    @DisplayName("문서 갱신이 실패하면 적재도 함께 되돌아간다")
    void aFailedDocumentUpdateRollsBackTheRecord() throws Exception {
        Document document = seed("롤백");
        doThrow(new DataAccessResourceFailureException("주입된 DB 실패"))
                .when(results).applyStorageLocation(anyLong(), anyString(), anyString());

        mockMvc.perform(submit(verified(document, "R2", "MINIO_LOCAL")))
                .andExpect(status().isInternalServerError());

        assertEquals(0, logCount(document), "반쯤 반영된 결과가 남으면 안 된다");
        assertEquals("R2", providerOf(document));
    }

    // ── 동시성 ──────────────────────────────────────────────────────────────

    /**
     * 같은 문서에 두 run 이 동시에 들어와도 문서는 한 번만 옮겨진다.
     *
     * <p>{@code FOR UPDATE} 가 순서를 만들고, source provider 비교가 두 번째 결과를 STALE 로
     * 가른다. 둘 중 하나만 있으면 이 단정이 깨진다.
     */
    @Test
    @DisplayName("동시 run 두 건은 로그 2행, 문서 1회 갱신이다")
    void twoSimultaneousRunsMoveTheDocumentOnce() throws Exception {
        Document document = seed("동시");
        Map<String, Object> first = verified(document, "R2", "MINIO_LOCAL");
        Map<String, Object> second = verified(document, "R2", "MINIO_LOCAL");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<Integer>> answers = List.of(
                    pool.submit(() -> perform(start, first)),
                    pool.submit(() -> perform(start, second)));
            start.countDown();
            for (java.util.concurrent.Future<Integer> answer : answers) {
                statuses.add(answer.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, logCount(document), "두 run 은 각각 기록된다");
        assertEquals(1, appliedCount(document), "문서를 옮긴 것은 하나뿐이다");
        assertEquals("MINIO_LOCAL", providerOf(document));
        assertTrue(statuses.contains(204), "하나는 반영되어야 한다: " + statuses);
        assertTrue(statuses.contains(409), "늦은 쪽은 STALE 이어야 한다: " + statuses);
    }

    private int perform(CountDownLatch start, Map<String, Object> payload) throws Exception {
        start.await();
        return mockMvc.perform(submit(payload)).andReturn().getResponse().getStatus();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private void assertRejected(Map<String, Object> payload, String field) throws Exception {
        mockMvc.perform(submit(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    private RequestBuilder submit(Map<String, Object> payload) {
        return submit(payload, INFRA_TOKEN);
    }

    private RequestBuilder submit(Map<String, Object> payload, String token) {
        return post("/internal/storage/reconciliation-runs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload));
    }

    private String json(Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(payload);
    }

    private Map<String, Object> verified(Document document, String source, String target) {
        return payload(document, source, target, "VERIFIED");
    }

    private static Map<String, Object> payload(Document document, String source, String target,
                                               String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", "run-" + UUID.randomUUID());
        payload.put("documentId", document.id());
        payload.put("objectKey", document.objectKey());
        payload.put("sourceProvider", source);
        payload.put("targetProvider", target);
        payload.put("status", status);
        payload.put("attemptCount", 1);
        payload.put("checkedAt", CHECKED_AT);
        return payload;
    }

    private Map<String, Object> with(Document document, String field, Object value) {
        Map<String, Object> payload = verified(document, "R2", "MINIO_LOCAL");
        payload.put(field, value);
        return payload;
    }

    private Map<String, Object> without(Document document, String field) {
        Map<String, Object> payload = verified(document, "R2", "MINIO_LOCAL");
        payload.remove(field);
        return payload;
    }

    private void moveTo(Document document, String provider, String bucket) {
        jdbc.update("UPDATE ai_documents SET storage_provider = ?, storage_bucket = ? WHERE id = ?",
                provider, bucket, document.id());
    }

    private String providerOf(Document document) {
        return jdbc.queryForObject("SELECT storage_provider FROM ai_documents WHERE id = ?",
                String.class, document.id());
    }

    private String bucketOf(Document document) {
        return jdbc.queryForObject("SELECT storage_bucket FROM ai_documents WHERE id = ?",
                String.class, document.id());
    }

    private String applyResultOf(Document document) {
        return jdbc.queryForObject(
                "SELECT apply_result FROM storage_reconciliation_log WHERE document_id = ?",
                String.class, document.id());
    }

    private int logCount(Document document) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM storage_reconciliation_log WHERE document_id = ?",
                Integer.class, document.id());
    }

    private int appliedCount(Document document) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM storage_reconciliation_log
                 WHERE document_id = ? AND apply_result = 'APPLIED'
                """, Integer.class, document.id());
    }

    private Document seed(String prefix) {
        String objectKey = "seed/reconcile/" + prefix + "/" + UUID.randomUUID();
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        long agentId = jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', 'ACTIVE')
                RETURNING id
                """, Long.class, boothId);
        Long documentId = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, 'READY', ?, ?,
                    'R2', '%s', now())
                RETURNING id
                """.formatted(R2_BUCKET), Long.class, boothId, agentId, objectKey, userId, SHA);
        assertNotNull(documentId);
        return new Document(documentId, objectKey);
    }

    private record Document(Long id, String objectKey) { }
}
