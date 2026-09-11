package com.example.ssafesta.internal.ai;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * {@code POST /internal/ai/document-jobs/{jobId}/chunk-batches}·{@code /finalize} — FastAPI 워커가
 * 만든 결과를 Spring 이 받는 경로 (S15P21A604-400, GitLab #119 §3).
 *
 * <p>고정하는 것은 <b>부분 반영이 없다</b>는 것이다. finalize 가 검증에서 걸리면 이전 chunk 가
 * 그대로 남고 문서는 READY 로 넘어가지 않는다 — 절반만 바뀐 문서가 답변에 인용되는 것이 이
 * 경로에서 제일 조용한 실패다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiDocumentResultApiIntegrationTest {

    /** {@code src/test/resources/application-local.properties} 가 넣는 값과 같아야 한다. */
    private static final String SERVICE_TOKEN = "test-ai-to-spring";

    private static final int DIMENSIONS = 1536;
    private static final String MODEL = "text-embedding-3-small";
    private static final String SOURCE_HASH = "%064x".formatted(7);

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiDocumentJobLeaseSweeper sweeper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 정상 경로 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("batch 두 번 뒤 finalize 하면 chunk 가 공개되고 문서가 READY 가 된다")
    void twoBatchesThenFinalizePublishesTheChunks() throws Exception {
        Job job = seedJob("정상");

        mockMvc.perform(batch(job, 0, chunk(0, "첫 조각"))).andExpect(status().isNoContent());
        mockMvc.perform(batch(job, 1, chunk(1, "둘째 조각"))).andExpect(status().isNoContent());
        mockMvc.perform(finalize(job, 2, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        assertEquals(2, chunkCount(job), "공개된 chunk 수");
        assertEquals(2, searchableCount(job), "searchable 로 올라간 chunk 수");
        assertEquals(0, stagingCount(job), "finalize 뒤 staging 은 비어야 한다");
        assertEquals("SUCCEEDED", jobStatus(job));
        assertEquals("READY", documentStatus(job));
        assertEquals(2, jobChunkCount(job));
        assertEquals(job.id(), chunkJobId(job), "chunk 에 출처 Job 이 남아야 한다");
    }

    /**
     * {@code READY} 로 갈 수 없는 문서의 finalize 는 <b>전부</b> 롤백되고 {@code 410} 이다
     * (T102 · FR-040a · SC-014).
     *
     * <p>이 자리가 오래 틀려 있었다. {@code markDocumentReady} 가 0행이면 ERROR 로그만 남기고
     * chunk 교체와 Job {@code SUCCEEDED} 는 커밋됐다 — 검색되지 않는 문서에 새 chunk 가 붙고 Job 은
     * 성공했다고 말한다. FR-040 이 요구한 "하나의 트랜잭션" 이 정확히 그 조합을 금지한다.
     *
     * <p>롤백하면 {@code SUCCEEDED} 표시도 취소되므로 재전송이 멱등 분기(Job 이 {@code SUCCEEDED}
     * 일 때만 진입)로 들어가지 못한다. 그래서 <b>같은 요청을 반복해도 무변화 410</b> 이고, 그것이
     * 사실이다 — 이 Job 의 어떤 attempt 도 {@code PROCESSING} 을 떠난 문서를 공개할 수 없다.
     *
     * <p>{@code DISABLED}·{@code EXPIRED} 를 {@code PROCESSING} 으로 되돌리는 복구는 spec 범위
     * 밖이라 여기서 재지 않는다.
     */
    @ParameterizedTest(name = "{0} 문서의 finalize 는 전부 롤백되고 410 이다")
    @ValueSource(strings = {"EXPIRED", "DISABLED"})
    void finalizeRollsBackEntirelyWhenTheDocumentCannotBePublished(String status) throws Exception {
        Job job = seedJob("공개불가" + status);
        seedChunk(job, 99, "예전 조각");
        mockMvc.perform(batch(job, 0, chunk(0, "새 조각"))).andExpect(status().isNoContent());
        jdbc.update("UPDATE ai_documents SET processing_status = ? WHERE id = ?",
                status, job.documentId());

        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));

        assertEquals(status, documentStatus(job), "공개할 수 없는 문서가 움직였다");
        assertEquals("RUNNING", jobStatus(job), "Job 이 SUCCEEDED 로 남았다 — 롤백되지 않았다");
        assertEquals(1, chunkCount(job), "옛 chunk 가 교체됐다 — 롤백되지 않았다");
        assertEquals(99, chunkNos(job).get(0), "남아 있어야 할 것은 교체 전 chunk 다");
        assertEquals(1, stagingCount(job), "staging 이 지워졌다 — 롤백되지 않았다");

        // 재전송도 같은 410 이고 아무것도 바뀌지 않는다. 롤백이 SUCCEEDED 를 남기지 않았으므로
        // finalize 의 멱등 분기로는 들어갈 수 없다.
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));
        assertEquals(status, documentStatus(job));
        assertEquals("RUNNING", jobStatus(job));
        assertEquals(1, chunkCount(job));
        assertEquals(1, stagingCount(job));
    }

    // ── 수정본 교체 (S15P21A604-386 · FR-027a) ───────────────────────────────

    /**
     * 교체본이 {@code READY} 가 된 그 트랜잭션에서 원본이 물러난다.
     *
     * <p>{@code replaced_at} 이 찍히는 유일한 자리다. 같은 {@code EXPIRED} 라도 이 칸이 있으면
     * 복구 대상이 아니고(FR-027a), 없으면 24시간 안에 완료로 되살아난다(FR-027).
     */
    @Test
    @DisplayName("교체본이 READY 가 되면 원본이 EXPIRED + replaced_at 으로 물러난다")
    void finalizingAReplacementRetiresTheOriginal() throws Exception {
        Job job = seedJob("교체본");
        long original = seedReplacedOriginal(job);

        mockMvc.perform(batch(job, 0, chunk(0, "새 조각"))).andExpect(status().isNoContent());
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        assertEquals("READY", documentStatus(job), "교체본이 공개되지 않았다");
        assertEquals("EXPIRED", statusOf(original), "원본이 물러나지 않았다");
        assertTrue(hasReplacedAt(original), "replaced_at 이 찍히지 않았다 — 복구 경로가 열려 있다");
        assertTrue(hasExpiredAt(original),
                "expired_at 이 없으면 원본 삭제 스윕이 이 행을 영영 집지 못한다 (FR-028)");
        assertEquals(0, chunksOf(original), "원본 chunk 가 남아 검색에 섞인다");
    }

    /**
     * 물리는 것은 <b>활성</b> Job 뿐이다.
     *
     * <p>정상 {@code READY} 문서의 과거 Job 은 {@code SUCCEEDED} 이고, 그것은 이 문서가 어떻게
     * 만들어졌는지의 기록이다. 싹 {@code CANCELLED} 로 덮으면 그 기록이 사라진다.
     */
    @Test
    @DisplayName("원본 퇴역은 활성 Job 만 취소하고 끝난 Job 은 건드리지 않는다")
    void retiringTheOriginalOnlyCancelsItsLiveJobs() throws Exception {
        Job job = seedJob("과거Job");
        long original = seedReplacedOriginal(job);
        long succeeded = seedJobRow(job, original, "SUCCEEDED");
        long live = seedJobRow(job, original, "RETRY_WAIT");

        mockMvc.perform(batch(job, 0, chunk(0, "새 조각"))).andExpect(status().isNoContent());
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        assertEquals("SUCCEEDED", jobStatusOf(succeeded), "끝난 Job 의 기록이 지워졌다");
        assertEquals("CANCELLED", jobStatusOf(live), "활성 Job 이 남았다 — 늦은 결과가 들어온다");
    }

    /** 처리가 실패하면 원본은 {@code READY} 그대로다 — 교체의 가장 중요한 단정이다. */
    @Test
    @DisplayName("교체본의 finalize 가 실패하면 원본은 READY 로 남는다")
    void aFailedReplacementLeavesTheOriginalReady() throws Exception {
        Job job = seedJob("교체실패");
        long original = seedReplacedOriginal(job);

        mockMvc.perform(batch(job, 0, chunk(0, "새 조각"))).andExpect(status().isNoContent());
        // 개수가 어긋난다 — finalize 검증에서 걸리는 정상적인 실패 경로다.
        mockMvc.perform(finalize(job, 2, SOURCE_HASH, MODEL))
                .andExpect(status().isBadRequest());

        assertEquals("READY", statusOf(original), "교체가 실패했는데 원본이 물러났다");
        assertTrue(!hasReplacedAt(original), "실패한 교체가 replaced_at 을 찍었다");
        assertEquals(1, chunksOf(original), "원본 chunk 가 지워졌다 — 답할 근거가 사라진다");
    }

    /**
     * 같은 batch 재전송은 아무것도 바꾸지 않는다.
     *
     * <p>staging PK 가 {@code (job_id, batch_seq, chunk_no)} 라 {@code ON CONFLICT DO NOTHING} 이
     * 흡수한다. 워커가 응답을 못 받고 다시 보내는 경우가 정상 경로라 이게 깨지면 finalize 개수가
     * 어긋난다.
     */
    @Test
    @DisplayName("같은 batch 를 다시 보내도 chunk 가 중복 적재되지 않는다")
    void resendingTheSameBatchIsIdempotent() throws Exception {
        Job job = seedJob("재전송");

        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());

        assertEquals(1, stagingCount(job));
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());
        assertEquals(1, chunkCount(job));
    }

    /**
     * 첫 batch 는 상태만 올리는 것이 아니라 <b>lease 도 잡는다.</b>
     *
     * <p>상태만 올리면 {@code lease_expires_at} 이 {@code NULL} 로 남고, sweeper 의 조건
     * {@code lease_expires_at < now()} 는 {@code NULL} 에 대해 참이 아니다 — 첫 heartbeat 전에
     * 죽은 워커의 Job 을 아무도 회수하지 못하고, 부분 unique 인덱스가 그 문서의 새 Job 도 막는다.
     */
    @Test
    @DisplayName("첫 batch 가 QUEUED Job 을 RUNNING 으로 올리고 lease 를 잡는다")
    void theFirstBatchMovesTheJobToRunningAndTakesTheLease() throws Exception {
        Job job = seedJob("기동");
        assertEquals("QUEUED", jobStatus(job));

        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());

        assertEquals("RUNNING", jobStatus(job));
        // 워커가 파일을 쥐었다는 첫 신호다 — 문서도 여기서 "처리 중" 이 된다 (S15P21A604-175).
        assertEquals("PROCESSING", documentStatus(job));
        long seconds = secondsUntilLeaseExpiry(job);
        assertTrue(seconds > 60 && seconds <= 90, "batch 도 lease 를 잡아야 한다: " + seconds);
    }

    /** heartbeat 를 한 번도 못 보내고 죽은 워커도 회수 대상이어야 한다. */
    @Test
    @DisplayName("heartbeat 없이 batch 만 보낸 뒤 죽은 워커의 Job 도 회수된다")
    void aWorkerThatDiedBeforeItsFirstHeartbeatIsReclaimed() throws Exception {
        Job job = seedJob("첫하트비트전사망");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        expireLease(job);

        sweeper.reclaimExpiredLeases();

        assertEquals("RETRY_WAIT", jobStatus(job));
        assertEquals(1, attemptNo(job));
    }

    /** 재처리다 — 이전 판의 chunk 는 남지 않는다. */
    @Test
    @DisplayName("finalize 는 문서의 기존 chunk 를 교체한다")
    void finalizeReplacesTheDocumentsExistingChunks() throws Exception {
        Job job = seedJob("교체");
        insertExistingChunk(job, 0, "옛 조각");

        mockMvc.perform(batch(job, 0, chunk(0, "새 조각"))).andExpect(status().isNoContent());
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        assertEquals(1, chunkCount(job));
        assertEquals("새 조각", jdbc.queryForObject(
                "SELECT content FROM ai_document_chunks WHERE document_id = ?", String.class,
                job.documentId()));
    }

    /**
     * finalize 응답을 못 받은 워커가 다시 보낸 경우다.
     *
     * <p>마지막 호출의 응답이 유실되는 것은 특별한 사고가 아니라 흔한 경우이고, #119 §3 이 이
     * 재전송을 멱등으로 요구한다. 410 으로 답하면 워커는 <b>성공한 작업을 실패로 보고한다.</b>
     */
    @Test
    @DisplayName("같은 finalize 를 다시 보내면 204 이고 아무것도 바뀌지 않는다")
    void resendingTheSameFinalizeIsIdempotent() throws Exception {
        Job job = seedJob("finalize재전송");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        assertEquals(1, chunkCount(job), "재전송이 chunk 를 다시 넣으면 안 된다");
        assertEquals("SUCCEEDED", jobStatus(job));
        assertEquals("READY", documentStatus(job));
    }

    /** 끝난 Job 이 한 일과 다른 주장이면 재전송이 아니다. */
    @Test
    @DisplayName("끝난 Job 에 다른 개수로 finalize 하면 410 이다")
    void aFinalizeThatContradictsTheFinishedJobIsGone() throws Exception {
        Job job = seedJob("다른개수");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL)).andExpect(status().isNoContent());

        mockMvc.perform(finalize(job, 7, SOURCE_HASH, MODEL))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));

        assertEquals(1, chunkCount(job));
    }

    // ── fencing ─────────────────────────────────────────────────────────────

    /**
     * lease 를 잃은 이전 워커의 결과다.
     *
     * <p>받아 주면 두 attempt 의 chunk 가 한 문서에 섞인다. 보내는 쪽은 자기가 밀려났다는 것을
     * 이 응답으로만 알 수 있다.
     */
    @Test
    @DisplayName("지난 attempt 의 batch 는 409 다")
    void aBatchFromAStaleAttemptIsRejected() throws Exception {
        Job job = seedJob("지난attempt");
        jdbc.update("UPDATE ai_document_jobs SET attempt_no = 1 WHERE id = ?", job.id());

        mockMvc.perform(batch(job, 0, chunk(0, "늦은 조각")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ATTEMPT_STALE"));

        assertEquals(0, stagingCount(job), "거부된 batch 가 적재되면 안 된다");
    }

    @Test
    @DisplayName("지난 attempt 의 finalize 는 409 다")
    void aFinalizeFromAStaleAttemptIsRejected() throws Exception {
        Job job = seedJob("지난finalize");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        jdbc.update("UPDATE ai_document_jobs SET attempt_no = 1 WHERE id = ?", job.id());

        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ATTEMPT_STALE"));

        assertEquals(0, chunkCount(job));
        assertEquals("PROCESSING", documentStatus(job));
    }

    @Test
    @DisplayName("취소된 Job 의 결과는 410 이다")
    void aResultForACancelledJobIsGone() throws Exception {
        Job job = seedJob("취소");
        jdbc.update("UPDATE ai_document_jobs SET status = 'CANCELLED' WHERE id = ?", job.id());

        mockMvc.perform(batch(job, 0, chunk(0, "조각")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));
    }

    /**
     * 임대 만료가 남긴 상태에 늦은 finalize 가 도착하는 경우 (S15P21A604-496).
     *
     * <p>만료는 Job 을 {@code CANCELLED}, 문서를 {@code DISABLED} 로 내린다. 그 뒤 취소 전달을
     * 못 받은 워커가 임베딩을 끝내고 finalize 를 보내와도 문서가 {@code READY} 로 되살아나면 안
     * 된다 — 만료된 부스의 문서가 검색에 다시 들어간다. {@code markDocumentReady} 의
     * {@code PROCESSING} 조건과 종료 Job 판정이 이중으로 막는 자리다.
     */
    @Test
    @DisplayName("만료로 취소된 Job 의 늦은 finalize 는 410 이고 문서는 DISABLED 로 남는다")
    void aLateFinalizeAfterLeaseExpiryCannotReviveTheDocument() throws Exception {
        Job job = seedJob("만료후finalize");
        jdbc.update("UPDATE ai_document_jobs SET status = 'CANCELLED' WHERE id = ?", job.id());
        jdbc.update("UPDATE ai_documents SET processing_status = 'DISABLED' WHERE id = ?",
                job.documentId());

        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));

        assertEquals("DISABLED", documentStatus(job));
        assertEquals(0, chunkCount(job));
    }

    /** 문서가 지워지면 Job 도 CASCADE 로 사라진다 — 없는 Job 과 같은 답이다. */
    @Test
    @DisplayName("없는 Job 의 결과는 410 이다")
    void aResultForAnUnknownJobIsGone() throws Exception {
        Job job = seedJob("없는Job");

        mockMvc.perform(post("/internal/ai/document-jobs/9999999/chunk-batches")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(0, chunk(0, "조각"))))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));

        // 이 결과는 다른 Job 으로 갔다. 이 문서는 batch 를 한 번도 못 받았으므로 그대로여야 한다.
        assertEquals("QUEUED", documentStatus(job));
    }

    // ── finalize 검증 ────────────────────────────────────────────────────────

    /**
     * 검증에서 걸리면 <b>아무것도 바뀌지 않는다.</b>
     *
     * <p>이 경로의 값어치가 전부 여기 있다 — batch 하나가 유실됐는데 finalize 를 받아 주면 문서가
     * 잘린 채로 READY 가 되고, 그 뒤로는 아무 오류도 나지 않는다.
     */
    @Test
    @DisplayName("적재된 개수와 다르면 400 이고 기존 chunk 와 문서 상태가 그대로다")
    void aCountMismatchChangesNothing() throws Exception {
        Job job = seedJob("개수불일치");
        insertExistingChunk(job, 0, "옛 조각");
        mockMvc.perform(batch(job, 0, chunk(0, "새 조각"))).andExpect(status().isNoContent());

        mockMvc.perform(finalize(job, 5, SOURCE_HASH, MODEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("totalChunkCount"));

        assertEquals("옛 조각", jdbc.queryForObject(
                "SELECT content FROM ai_document_chunks WHERE document_id = ?", String.class,
                job.documentId()));
        assertEquals("PROCESSING", documentStatus(job));
        assertEquals("RUNNING", jobStatus(job));
        assertEquals(1, stagingCount(job), "staging 도 남아야 다시 finalize 할 수 있다");
    }

    /** batch 가 다르면 staging PK 가 막지 못한다 — 그대로 넣으면 UNIQUE 위반으로 500 이 된다. */
    @Test
    @DisplayName("batch 간 chunkNo 중복은 400 이다")
    void aChunkNumberRepeatedAcrossBatchesIsRejected() throws Exception {
        Job job = seedJob("번호중복");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        mockMvc.perform(batch(job, 1, chunk(0, "같은 번호"))).andExpect(status().isNoContent());

        mockMvc.perform(finalize(job, 2, SOURCE_HASH, MODEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chunks"));

        assertEquals(0, chunkCount(job));
    }

    @Test
    @DisplayName("임베딩 모델이 섞이면 400 이다")
    void mixedEmbeddingModelsAreRejected() throws Exception {
        Job job = seedJob("모델혼합");
        mockMvc.perform(batch(job, 0, chunk(0, "조각", MODEL))).andExpect(status().isNoContent());
        mockMvc.perform(batch(job, 1, chunk(1, "조각", "other-model")))
                .andExpect(status().isNoContent());

        mockMvc.perform(finalize(job, 2, SOURCE_HASH, MODEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("embeddingModelId"));
    }

    /** 같은 jobId 인데 다른 파일을 처리했다는 뜻이다 — 받아 주면 문서 본문이 조용히 바뀐다. */
    @Test
    @DisplayName("sourceHash 가 Job 과 다르면 400 이다")
    void aSourceHashMismatchIsRejected() throws Exception {
        Job job = seedJob("해시불일치");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());

        mockMvc.perform(finalize(job, 1, "%064x".formatted(99), MODEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sourceHash"));

        assertEquals("PROCESSING", documentStatus(job));
    }

    // ── batch 검증 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("한 batch 안의 chunkNo 중복은 400 이다")
    void aChunkNumberRepeatedInsideOneBatchIsRejected() throws Exception {
        Job job = seedJob("배치내중복");

        mockMvc.perform(batch(job, 0, chunk(0, "하나"), chunk(0, "둘")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chunks[1].chunkNo"));
    }

    @Test
    @DisplayName("차원이 1536 이 아닌 임베딩은 400 이다")
    void anEmbeddingOfTheWrongDimensionIsRejected() throws Exception {
        Job job = seedJob("차원");
        String body = """
                {"attemptNo":0,"batchSeq":0,"chunks":[{"chunkNo":0,"content":"조각",
                 "embedding":%s,"embeddingModelId":"%s","pageNumber":null,"section":null}]}
                """.formatted(vector(DIMENSIONS - 1), MODEL);

        mockMvc.perform(chunkBatchRequest(job, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chunks[0].embedding"));
    }

    @Test
    @DisplayName("한 batch 의 chunk 상한은 200 개다")
    void aBatchLargerThanTwoHundredChunksIsRejected() throws Exception {
        Job job = seedJob("상한");
        StringBuilder chunks = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            chunks.append(i == 0 ? "" : ",").append(chunk(i, "조각"));
        }
        String body = """
                {"attemptNo":0,"batchSeq":0,"chunks":[%s]}
                """.formatted(chunks);

        mockMvc.perform(chunkBatchRequest(job, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chunks"));
    }

    /** 조용히 버리면 보내는 쪽은 그 값이 반영됐다고 믿는다 (T-24 의 모양). */
    @Test
    @DisplayName("계약에 없는 필드는 400 으로 거부된다")
    void anUnknownFieldIsRejectedRatherThanDropped() throws Exception {
        Job job = seedJob("미지필드");
        String body = """
                {"attemptNo":0,"batchSeq":0,"batchChecksum":"abc","chunks":[%s]}
                """.formatted(chunk(0, "조각"));

        mockMvc.perform(chunkBatchRequest(job, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("batchChecksum"));
    }

    @Test
    @DisplayName("chunk 가 0 개인 batch 는 400 이다")
    void anEmptyBatchIsRejected() throws Exception {
        Job job = seedJob("빈배치");

        mockMvc.perform(chunkBatchRequest(job, """
                {"attemptNo":0,"batchSeq":0,"chunks":[]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chunks"));
    }

    // ── heartbeat ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("heartbeat 가 lease 를 90초 뒤로 밀고 QUEUED 를 RUNNING 으로 올린다")
    void aHeartbeatExtendsTheLease() throws Exception {
        Job job = seedJob("하트비트");

        mockMvc.perform(heartbeat(job, 0)).andExpect(status().isNoContent());

        assertEquals("RUNNING", jobStatus(job));
        // batch 가 아니라 heartbeat 가 먼저 오는 순서도 실재한다. 문서 전이가 두 신호가 공유하는
        // extendLease 에 있어야 이 단정이 선다 — acceptBatch 에 넣으면 여기서 깨진다.
        assertEquals("PROCESSING", documentStatus(job));
        long seconds = secondsUntilLeaseExpiry(job);
        assertTrue(seconds > 60 && seconds <= 90, "lease 가 90초 근처여야 한다: " + seconds);
    }

    @Test
    @DisplayName("지난 attempt 의 heartbeat 는 409 다")
    void aHeartbeatFromAStaleAttemptIsRejected() throws Exception {
        Job job = seedJob("하트비트지난");
        jdbc.update("UPDATE ai_document_jobs SET attempt_no = 1 WHERE id = ?", job.id());

        mockMvc.perform(heartbeat(job, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ATTEMPT_STALE"));
    }

    @Test
    @DisplayName("끝난 Job 의 heartbeat 는 410 이다")
    void aHeartbeatForAFinishedJobIsGone() throws Exception {
        Job job = seedJob("하트비트종료");
        jdbc.update("UPDATE ai_document_jobs SET status = 'DEAD' WHERE id = ?", job.id());

        mockMvc.perform(heartbeat(job, 0))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("JOB_GONE"));
    }

    // ── failed ──────────────────────────────────────────────────────────────

    /** 재시도 여지가 남아 있으면 다음 attempt 를 예약한다 — 1분 뒤(#119 backoff 1·5·15분). */
    @Test
    @DisplayName("재시도 가능한 실패는 RETRY_WAIT 로 가고 attempt 가 오른다")
    void aRetryableFailureSchedulesTheNextAttempt() throws Exception {
        Job job = seedJob("재시도가능");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());

        mockMvc.perform(failed(job, 0, "PARSE_TIMEOUT", true, "10분 안에 못 끝냈습니다."))
                .andExpect(status().isNoContent());

        assertEquals("RETRY_WAIT", jobStatus(job));
        // attempt 가 실패한 것이지 문서가 실패한 것이 아니다. 다음 attempt 가 예약돼 있고 그
        // finalize 가 아직 이 문서를 공개해야 하므로, 여기서 FAILED 로 찍으면 살아 있는 Job 에
        // 죽은 문서가 붙는다.
        assertEquals("PROCESSING", documentStatus(job));
        assertEquals(1, attemptNo(job));
        assertEquals("PARSE_TIMEOUT", lastErrorCode(job));
        assertEquals(0, stagingCount(job), "죽은 attempt 의 staging 은 남으면 안 된다");
        long seconds = secondsUntilNextRetry(job);
        assertTrue(seconds > 30 && seconds <= 60, "backoff 가 1분 근처여야 한다: " + seconds);
    }

    /** 손상된 파일은 세 번 더 해 봐야 똑같이 깨진다. */
    @Test
    @DisplayName("재시도 불가 실패는 남은 횟수와 무관하게 DEAD 다")
    void aNonRetryableFailureGoesStraightToDead() throws Exception {
        Job job = seedJob("재시도불가");

        mockMvc.perform(failed(job, 0, "CORRUPT_PDF", false, null))
                .andExpect(status().isNoContent());

        assertEquals("DEAD", jobStatus(job));
        // batch 가 한 번도 없었으므로 문서는 아직 QUEUED 였다 — 그 자리에서도 실패로 내려간다.
        assertEquals("FAILED", documentStatus(job), "죽은 Job 의 문서가 대기 중으로 남았다");
        assertEquals(1, attemptNo(job), "DEAD 여도 attempt 는 올라야 늦은 결과가 막힌다");
    }

    @Test
    @DisplayName("재시도 횟수를 다 쓰면 DEAD 다")
    void anExhaustedRetryBudgetEndsInDead() throws Exception {
        Job job = seedJob("횟수소진");
        jdbc.update("UPDATE ai_document_jobs SET attempt_no = 3, max_retries = 3 WHERE id = ?",
                job.id());

        mockMvc.perform(failed(job, 3, "PARSE_TIMEOUT", true, null))
                .andExpect(status().isNoContent());

        assertEquals("DEAD", jobStatus(job));
        assertEquals("FAILED", documentStatus(job));
        assertEquals(4, attemptNo(job));
    }

    /** {@code last_error_code} 가 VARCHAR(50) 이다 — 넘기면 400 이지 500 이 아니다. */
    @Test
    @DisplayName("50자를 넘는 failureCode 는 400 이다")
    void anOverlongFailureCodeIsRejected() throws Exception {
        Job job = seedJob("긴코드");

        mockMvc.perform(failed(job, 0, "X".repeat(51), true, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("failureCode"));

        assertEquals("QUEUED", jobStatus(job));
    }

    @Test
    @DisplayName("retryable 이 없으면 400 이다")
    void aFailureWithoutRetryableIsRejected() throws Exception {
        Job job = seedJob("판단없음");

        mockMvc.perform(post("/internal/ai/document-jobs/" + job.id() + "/failed")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"attemptNo":0,"failureCode":"PARSE_TIMEOUT","message":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("retryable"));
    }

    /** 실패 보고가 attempt 를 올렸으므로, 그 워커가 뒤늦게 보내는 결과는 이제 남의 것이다. */
    @Test
    @DisplayName("실패 보고 뒤 같은 워커의 batch 는 409 다")
    void aBatchAfterTheAttemptFailedIsRejected() throws Exception {
        Job job = seedJob("실패후배치");
        mockMvc.perform(failed(job, 0, "PARSE_TIMEOUT", true, null))
                .andExpect(status().isNoContent());

        mockMvc.perform(batch(job, 0, chunk(0, "늦은 조각")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ATTEMPT_STALE"));
    }

    // ── lease 회수 ───────────────────────────────────────────────────────────

    /**
     * 워커가 죽으면 아무도 Job 을 놓아 주지 않는다.
     *
     * <p>FastAPI 는 DB 자격증명이 없어 스스로 반납할 수 없고, 회수가 없으면 문서는 영원히
     * READY 가 되지 않는다. 그리고 회수가 {@code attempt_no} 를 올리는 것이 <b>얼어 있다 깨어난
     * 워커</b>를 막는 유일한 수단이다.
     */
    @Test
    @DisplayName("lease 가 만료된 RUNNING Job 을 회수한다")
    void anExpiredLeaseIsReclaimed() throws Exception {
        Job job = seedJob("회수");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        expireLease(job);

        sweeper.reclaimExpiredLeases();

        assertEquals("RETRY_WAIT", jobStatus(job));
        assertEquals(1, attemptNo(job));
        assertEquals("LEASE_EXPIRED", lastErrorCode(job));
        assertEquals(0, stagingCount(job), "죽은 attempt 의 staging 은 남으면 안 된다");
    }

    /**
     * 회수된 뒤 재시도 attempt 가 보낸 신호도 Job 을 RUNNING 으로 올려야 한다.
     *
     * <p>sweeper 는 {@code status = 'RUNNING'} 만 본다({@code ix_ai_document_jobs_lease}). 재시도
     * attempt 가 RETRY_WAIT 에 머무르면 그 워커가 죽어도 아무도 회수하지 못하고, 부분 unique
     * 인덱스가 RETRY_WAIT 를 살아 있는 Job 으로 세므로 그 문서의 새 Job 도 영구히 막힌다.
     */
    @Test
    @DisplayName("회수된 Job 의 다음 attempt 도 RUNNING 으로 올라가고 다시 회수될 수 있다")
    void aRetriedAttemptBecomesRunningAndStaysReclaimable() throws Exception {
        Job job = seedJob("재시도회수");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        expireLease(job);
        sweeper.reclaimExpiredLeases();
        assertEquals("RETRY_WAIT", jobStatus(job));
        assertEquals(1, attemptNo(job));

        mockMvc.perform(heartbeat(job, 1)).andExpect(status().isNoContent());

        assertEquals("RUNNING", jobStatus(job), "재시도 attempt 가 RETRY_WAIT 에 머물면 sweeper 가 못 본다");

        expireLease(job);
        sweeper.reclaimExpiredLeases();

        assertEquals(2, attemptNo(job), "두 번째 워커가 죽어도 회수돼야 한다");
    }

    @Test
    @DisplayName("lease 가 살아 있는 Job 은 건드리지 않는다")
    void aLiveLeaseIsLeftAlone() throws Exception {
        Job job = seedJob("살아있음");
        mockMvc.perform(heartbeat(job, 0)).andExpect(status().isNoContent());

        sweeper.reclaimExpiredLeases();

        assertEquals("RUNNING", jobStatus(job));
        assertEquals(0, attemptNo(job));
    }

    @Test
    @DisplayName("재시도 횟수를 다 쓴 Job 의 회수는 DEAD 로 끝난다")
    void reclaimingAJobWithNoRetriesLeftKillsIt() throws Exception {
        Job job = seedJob("회수DEAD");
        jdbc.update("""
                UPDATE ai_document_jobs SET status = 'RUNNING', attempt_no = 3, max_retries = 3
                 WHERE id = ?
                """, job.id());
        expireLease(job);

        sweeper.reclaimExpiredLeases();

        assertEquals("DEAD", jobStatus(job));
        // 회수는 벌크라 경로가 다르다 — 회수분 중 DEAD 가 된 것만 문서를 따라 내린다.
        assertEquals("FAILED", documentStatus(job));
        assertEquals(4, attemptNo(job));
    }

    /** 회수된 뒤 깨어난 워커의 finalize 는 이제 남의 attempt 다. */
    @Test
    @DisplayName("회수 뒤 이전 attempt 의 finalize 는 409 다")
    void aFinalizeFromAReclaimedAttemptIsRejected() throws Exception {
        Job job = seedJob("회수후finalize");
        mockMvc.perform(batch(job, 0, chunk(0, "조각"))).andExpect(status().isNoContent());
        expireLease(job);
        sweeper.reclaimExpiredLeases();

        mockMvc.perform(finalize(job, 1, SOURCE_HASH, MODEL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ATTEMPT_STALE"));

        assertEquals("PROCESSING", documentStatus(job));
    }

    // ── 인증 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없으면 401")
    void aMissingServiceTokenIsRefused() throws Exception {
        Job job = seedJob("토큰없음");

        mockMvc.perform(post("/internal/ai/document-jobs/" + job.id() + "/chunk-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(0, chunk(0, "조각"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 반대 방향 토큰은 통하지 않는다 (GitLab #102). */
    @Test
    @DisplayName("반대 방향 토큰은 401")
    void theOppositeDirectionTokenIsRefused() throws Exception {
        Job job = seedJob("반대토큰");

        mockMvc.perform(post("/internal/ai/document-jobs/" + job.id() + "/finalize")
                        .header("Authorization", "Bearer test-spring-to-ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(finalizeBody(1, SOURCE_HASH, MODEL)))
                .andExpect(status().isUnauthorized());
    }

    // ── 요청 헬퍼 ────────────────────────────────────────────────────────────

    private RequestBuilder batch(Job job, int batchSeq, String... chunks) {
        return chunkBatchRequest(job, batchBody(batchSeq, chunks));
    }

    private RequestBuilder chunkBatchRequest(Job job, String body) {
        return post("/internal/ai/document-jobs/" + job.id() + "/chunk-batches")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private RequestBuilder finalize(Job job, int totalChunkCount, String sourceHash, String model) {
        return post("/internal/ai/document-jobs/" + job.id() + "/finalize")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(finalizeBody(totalChunkCount, sourceHash, model));
    }

    private RequestBuilder heartbeat(Job job, int attemptNo) {
        return post("/internal/ai/document-jobs/" + job.id() + "/heartbeat")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptNo\":%d}".formatted(attemptNo));
    }

    private RequestBuilder failed(Job job, int attemptNo, String failureCode, Boolean retryable,
                                  String message) {
        String body = """
                {"attemptNo":%d,"failureCode":"%s","retryable":%s,"message":%s}
                """.formatted(attemptNo, failureCode, retryable,
                        message == null ? "null" : "\"" + message + "\"");
        return post("/internal/ai/document-jobs/" + job.id() + "/failed")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void expireLease(Job job) {
        jdbc.update("""
                UPDATE ai_document_jobs SET lease_expires_at = now() - INTERVAL '1 minute'
                 WHERE id = ?
                """, job.id());
    }

    private int attemptNo(Job job) {
        return jdbc.queryForObject("SELECT attempt_no FROM ai_document_jobs WHERE id = ?",
                Integer.class, job.id());
    }

    private String lastErrorCode(Job job) {
        return jdbc.queryForObject("SELECT last_error_code FROM ai_document_jobs WHERE id = ?",
                String.class, job.id());
    }

    private long secondsUntilLeaseExpiry(Job job) {
        return jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (lease_expires_at - now()))::bigint
                  FROM ai_document_jobs WHERE id = ?
                """, Long.class, job.id());
    }

    private long secondsUntilNextRetry(Job job) {
        return jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (next_retry_at - now()))::bigint
                  FROM ai_document_jobs WHERE id = ?
                """, Long.class, job.id());
    }

    private static String batchBody(int batchSeq, String... chunks) {
        return """
                {"attemptNo":0,"batchSeq":%d,"chunks":[%s]}
                """.formatted(batchSeq, String.join(",", chunks));
    }

    private static String finalizeBody(int totalChunkCount, String sourceHash, String model) {
        return """
                {"attemptNo":0,"sourceHash":"%s","totalChunkCount":%d,"embeddingModelId":"%s"}
                """.formatted(sourceHash, totalChunkCount, model);
    }

    private static String chunk(int chunkNo, String content) {
        return chunk(chunkNo, content, MODEL);
    }

    private static String chunk(int chunkNo, String content, String model) {
        return """
                {"chunkNo":%d,"content":"%s","embedding":%s,"embeddingModelId":"%s",
                 "pageNumber":null,"section":null}
                """.formatted(chunkNo, content, vector(DIMENSIONS), model);
    }

    /** 첫 칸만 1 인 단위 벡터 — 노름이 0 도 아니고 넘치지도 않는다. */
    private static String vector(int dimensions) {
        StringBuilder vector = new StringBuilder("[1");
        vector.append(",0".repeat(Math.max(0, dimensions - 1)));
        return vector.append(']').toString();
    }

    // ── 확인 헬퍼 ────────────────────────────────────────────────────────────

    private int chunkCount(Job job) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_document_chunks WHERE document_id = ?",
                Integer.class, job.documentId());
    }

    private int searchableCount(Job job) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_document_chunks WHERE document_id = ? AND searchable = TRUE
                """, Integer.class, job.documentId());
    }

    private int stagingCount(Job job) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_document_chunk_staging WHERE job_id = ?", Integer.class,
                job.id());
    }

    private String jobStatus(Job job) {
        return jdbc.queryForObject("SELECT status FROM ai_document_jobs WHERE id = ?", String.class,
                job.id());
    }

    private Integer jobChunkCount(Job job) {
        return jdbc.queryForObject("SELECT chunk_count FROM ai_document_jobs WHERE id = ?",
                Integer.class, job.id());
    }

    private String documentStatus(Job job) {
        return jdbc.queryForObject("SELECT processing_status FROM ai_documents WHERE id = ?",
                String.class, job.documentId());
    }

    private Long chunkJobId(Job job) {
        return jdbc.queryForObject(
                "SELECT DISTINCT job_id FROM ai_document_chunks WHERE document_id = ?", Long.class,
                job.documentId());
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void insertExistingChunk(Job job, int chunkNo, String content) {
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, searchable)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?, TRUE)
                """, job.documentId(), job.boothId(), job.agentId(), chunkNo, content,
                vector(DIMENSIONS), MODEL);
    }

    /**
     * {@code s3_key} 는 UNIQUE 이고 컨테이너 DB 는 <b>테스트 클래스 사이에서 공유된다</b> — prefix
     * 만 쓰면 다른 클래스가 같은 이름("정상"·"상한" 등)을 쓸 때 전체 실행에서만 깨진다. 클래스
     * 단위로 돌리면 통과하는 종류라 값 자체를 전역 유일하게 만든다.
     */
    // ── 수정본 교체 준비·확인 ────────────────────────────────────────────────

    /**
     * {@code job} 의 문서가 교체본이 되도록 원본 {@code READY} 문서를 하나 세우고 연결한다.
     *
     * <p>{@code /replacement} 를 거치지 않는다 — 이 파일이 보는 것은 finalize 가 그 연결을 어떻게
     * 처리하는가이지 연결이 어떻게 생기는가가 아니다. 발급 경로는
     * {@code AiDocumentUploadIntegrationTest} 가 본다.
     *
     * @return 원본 문서 id
     */
    private long seedReplacedOriginal(Job job) {
        long original = jdbc.queryForObject("""
                INSERT INTO ai_documents (booth_id, agent_id, original_filename, content_type,
                    size_bytes, s3_key, processing_status, uploaded_by_user_id, content_sha256,
                    storage_provider, storage_bucket, uploaded_at)
                VALUES (?, ?, 'original.pdf', 'application/pdf', 1024, ?, 'READY',
                    (SELECT uploaded_by_user_id FROM ai_documents WHERE id = ?), ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, job.boothId(), job.agentId(),
                "seed/original/" + UUID.randomUUID(), job.documentId(), "%064x".formatted(31));
        jdbc.update("UPDATE ai_documents SET replaces_document_id = ? WHERE id = ?",
                original, job.documentId());
        seedChunkFor(job, original, 0, "원본 조각");
        return original;
    }

    /** 교체 전에 이미 공개돼 있던 chunk — 롤백·퇴역이 이 행을 어떻게 다루는지가 단정 대상이다. */
    private void seedChunk(Job job, int chunkNo, String content) {
        seedChunkFor(job, job.documentId(), chunkNo, content);
    }

    private void seedChunkFor(Job job, long documentId, int chunkNo, String content) {
        jdbc.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, chunk_no, content,
                    embedding, embedding_model_id, searchable)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?, TRUE)
                """, documentId, job.boothId(), job.agentId(), chunkNo, content,
                vector(DIMENSIONS), MODEL);
    }

    /** 원본에 딸린 Job 을 상태만 정해 하나 더 만든다 — 퇴역이 무엇을 취소하는지 가르는 용도다. */
    private long seedJobRow(Job job, long documentId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO ai_document_jobs (document_id, booth_id, agent_id, source_hash,
                    original_filename, content_type, file_size_bytes, storage_provider,
                    storage_bucket, object_key, status, attempt_no)
                VALUES (?, ?, ?, ?, 'original.pdf', 'application/pdf', 1024, 'R2',
                    'test-ai-documents', ?, ?, 0)
                RETURNING id
                """, Long.class, documentId, job.boothId(), job.agentId(), SOURCE_HASH,
                "seed/original-job/" + UUID.randomUUID(), status);
    }

    private String statusOf(long documentId) {
        return jdbc.queryForObject("SELECT processing_status FROM ai_documents WHERE id = ?",
                String.class, documentId);
    }

    private boolean hasReplacedAt(long documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT replaced_at IS NOT NULL FROM ai_documents WHERE id = ?", Boolean.class,
                documentId));
    }

    private boolean hasExpiredAt(long documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT expired_at IS NOT NULL FROM ai_documents WHERE id = ?", Boolean.class,
                documentId));
    }

    private int chunksOf(long documentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_document_chunks WHERE document_id = ?",
                Integer.class, documentId);
    }

    private List<Integer> chunkNos(Job job) {
        return jdbc.queryForList("""
                SELECT chunk_no FROM ai_document_chunks WHERE document_id = ? ORDER BY chunk_no
                """, Integer.class, job.documentId());
    }

    private String jobStatusOf(long jobId) {
        return jdbc.queryForObject("SELECT status FROM ai_document_jobs WHERE id = ?", String.class,
                jobId);
    }

    private Job seedJob(String prefix) {
        String objectKey = "seed/result/" + prefix + "/" + UUID.randomUUID();
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
                VALUES (?, ?, 'seed.pdf', 'application/pdf', 1024, ?, 'QUEUED', ?, ?,
                    'R2', 'test-ai-documents', now())
                RETURNING id
                """, Long.class, boothId, agentId, objectKey, userId, SOURCE_HASH);
        long jobId = jdbc.queryForObject("""
                INSERT INTO ai_document_jobs (document_id, booth_id, agent_id, source_hash,
                    original_filename, content_type, file_size_bytes, storage_provider,
                    storage_bucket, object_key, status, attempt_no)
                VALUES (?, ?, ?, ?, 'seed.pdf', 'application/pdf', 1024, 'R2',
                    'test-ai-documents', ?, 'QUEUED', 0)
                RETURNING id
                """, Long.class, documentId, boothId, agentId, SOURCE_HASH, objectKey);
        return new Job(jobId, documentId, boothId, agentId);
    }

    private record Job(long id, Long documentId, Long boothId, long agentId) { }
}
