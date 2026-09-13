package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.internal.ai.AiDocumentJobRepository.JobRow;
import com.example.ssafesta.internal.ai.AiDocumentJobRepository.StagedChunk;
import com.example.ssafesta.internal.ai.AiDocumentJobRepository.StagingSummary;
import com.example.ssafesta.project.Project;
import com.example.ssafesta.project.ProjectRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives a worker's results and decides whether they still count (S15P21A604-400, GitLab #119 §3,
 * {@code specs/007-ai-agent-document/contracts/document-result-contract.md}).
 *
 * <p>Four operations — staged batches, the finalize that publishes them, the heartbeat that keeps
 * an attempt alive, and the failure that ends one. FastAPI holds no database credential, so
 * everything a result changes happens here.
 *
 * <p>Every entry point passes the same two gates first: the {@code attemptNo} must be the one that
 * owns the Job ({@code 409} otherwise), and the Job must not be finished ({@code 410} otherwise).
 * Without the first gate a worker that lost its lease, and does not know it, overwrites the work of
 * the attempt that replaced it.
 *
 * <p>finalize is also where a 수정본 교체 lands (FR-027a): publishing the new version is the moment
 * the original it replaced stops being the answer, so retiring that original is the last statement
 * of the same transaction. Everything before it failing means the original is untouched.
 *
 * <p>finalize has one exception to the second gate: a Job this same attempt already finished, with
 * the same file and the same count, is a <b>resend</b> and answers {@code 204}. Losing the response
 * to the last call of a job is the ordinary case, and #119 §3 requires the resend to be idempotent —
 * answering {@code 410} would make a worker report a failure for work that succeeded.
 */
@Service
public class AiDocumentResultService {

    /** GitLab #119 §3-2, 2026-09-02 합의. 8 MiB 쪽은 본문 크기라 서버가 본다. */
    private static final int MAX_CHUNKS_PER_BATCH = 200;

    /** {@code last_error_code} 컬럼이 VARCHAR(50) 이다. */
    private static final int MAX_FAILURE_CODE_LENGTH = 50;

    private static final String SUCCEEDED = "SUCCEEDED";

    private static final Set<String> TERMINAL = Set.of(SUCCEEDED, "DEAD", "CANCELLED");

    /**
     * 정형 정보를 받을 수 없는 상태. {@link #TERMINAL} 과 달리 {@code SUCCEEDED} 가 빠져 있다 —
     * 추출은 임베딩이 <b>끝난 뒤</b> 오므로 성공한 Job 이 정상이다 (S15P21A604-597).
     */
    private static final Set<String> ABANDONED = Set.of("DEAD", "CANCELLED");

    /** 프롬프트에 실릴 값이다. 넘치면 조용히 자르지 않고 400 으로 답한다. */
    private static final int MAX_FACT_LENGTH = 2000;

    private final AiDocumentJobRepository jobs;
    private final ProjectRepository projects;

    AiDocumentResultService(AiDocumentJobRepository jobs, ProjectRepository projects) {
        this.jobs = jobs;
        this.projects = projects;
    }

    @Transactional
    public void acceptBatch(long jobId, String rawBody) {
        ChunkBatchRequest request = StrictJsonReader.read(rawBody, ChunkBatchRequest.class);
        int batchSeq = nonNegative(request.batchSeq(), "batchSeq");
        List<StagedChunk> staged = staged(request.chunks());

        JobRow job = live(jobId, request.attemptNo());
        // batch 도 살아 있다는 신호다 — heartbeat 와 같은 자리에서 lease 를 잡는다. 상태만 올리고
        // lease 를 비워 두면 첫 heartbeat 전에 죽은 워커의 Job 을 sweeper 가 영영 못 본다.
        jobs.extendLease(job.id(), job.documentId());
        jobs.stageChunks(job.id(), batchSeq, staged);
    }

    /**
     * Verifies the staged set against what the worker says it sent, then publishes it.
     *
     * <p>The checks are the reason staging exists. A batch that never arrived, a batch that arrived
     * twice under different {@code batchSeq}, a worker that changed embedding model mid-job — each
     * produces a chunk set that looks fine row by row and is wrong as a whole. They are caught here,
     * before the old chunks are deleted, so a failed finalize leaves the previous version intact.
     */
    @Transactional
    public void finalizeJob(long jobId, String rawBody) {
        FinalizeRequest request = StrictJsonReader.read(rawBody, FinalizeRequest.class);
        int expected = positive(request.totalChunkCount(), "totalChunkCount");
        String modelId = required(request.embeddingModelId(), "embeddingModelId");
        String sourceHash = required(request.sourceHash(), "sourceHash");

        JobRow job = locked(jobId, request.attemptNo());
        if (SUCCEEDED.equals(job.status())) {
            // 응답을 못 받은 워커의 재전송이다. 같은 attempt 가 같은 파일을 같은 개수로 끝냈다면
            // 할 일은 이미 다 돼 있다 — #119 §3 이 finalize 재전송을 멱등으로 요구한다.
            // 숫자가 다르면 이 Job 이 한 일과 다른 주장이므로 끝난 Job 취급이다.
            if (sourceHash.equals(job.sourceHash()) && Integer.valueOf(expected).equals(job.chunkCount())) {
                return;
            }
            throw new ApiException(ErrorCode.JOB_GONE);
        }
        if (TERMINAL.contains(job.status())) {
            throw new ApiException(ErrorCode.JOB_GONE);
        }
        if (!job.sourceHash().equals(sourceHash)) {
            // 같은 jobId 인데 다른 파일을 처리했다는 뜻이다. 받아 주면 문서 본문이 조용히 바뀐다.
            throw ApiException.fieldInvalid("sourceHash", "Job 의 원본 해시와 다릅니다.");
        }

        StagingSummary staging = jobs.summarise(job.id());
        if (staging.total() != expected) {
            throw ApiException.fieldInvalid("totalChunkCount",
                    "적재된 chunk 는 " + staging.total() + "개입니다. 누락된 batch 가 있는지 확인해 주세요.");
        }
        if (staging.distinctChunkNo() != staging.total()) {
            // batch 가 서로 다른데 chunk_no 가 겹친다. 그대로 넣으면 UNIQUE(document_id, chunk_no)
            // 위반으로 500 이 되므로, 원인을 말할 수 있는 여기서 막는다.
            throw ApiException.fieldInvalid("chunks", "chunkNo 가 batch 간에 중복됩니다.");
        }
        if (staging.modelCount() != 1 || !modelId.equals(staging.modelId())) {
            // 한 문서의 chunk 가 서로 다른 모델로 임베딩되면 거리 비교 자체가 뜻을 잃는다.
            throw ApiException.fieldInvalid("embeddingModelId",
                    "적재된 chunk 의 임베딩 모델과 다릅니다.");
        }

        jobs.replaceChunks(job);
        jobs.clearStaging(job.id());
        jobs.markSucceeded(job.id(), staging.total());
        jobs.markDocumentReady(job.documentId());
        // 마지막이다. 이 문서가 실제로 READY 가 된 뒤라야 밀려난 원본을 물릴 수 있다 (FR-027a) —
        // 위에서 하나라도 걸리면 원본은 READY 그대로이고 AI 직원은 답할 근거를 잃지 않는다.
        jobs.retireReplacedOriginal(job.documentId());
    }

    /**
     * Extends the lease so the reclaim sweeper leaves this attempt alone.
     *
     * <p>The worker sends one every 30 seconds and the lease is 90 (GitLab #119) — two may be lost
     * before the Job is taken back.
     */
    @Transactional
    public void heartbeat(long jobId, String rawBody) {
        HeartbeatRequest request = StrictJsonReader.read(rawBody, HeartbeatRequest.class);
        JobRow job = live(jobId, request.attemptNo());
        jobs.extendLease(job.id(), job.documentId());
    }

    /**
     * Ends the attempt the worker says it cannot finish.
     *
     * <p>Spring decides what happens next, not the worker: it reports <i>what</i> broke and whether
     * another attempt could help, and the retry budget lives here with the Job.
     */
    @Transactional
    public void reportFailure(long jobId, String rawBody) {
        FailedRequest request = StrictJsonReader.read(rawBody, FailedRequest.class);
        String failureCode = required(request.failureCode(), "failureCode");
        if (failureCode.length() > MAX_FAILURE_CODE_LENGTH) {
            // last_error_code 는 VARCHAR(50) 이다. 넘겨서 보내면 여기서 400 이 아니라 저장에서
            // 500 이 된다 — 보내는 쪽이 고칠 수 있는 실수라 400 으로 답한다.
            throw ApiException.fieldInvalid("failureCode",
                    MAX_FAILURE_CODE_LENGTH + "자 이하여야 합니다.");
        }
        if (request.retryable() == null) {
            throw ApiException.fieldInvalid("retryable", "값이 필요합니다.");
        }

        JobRow job = live(jobId, request.attemptNo());
        jobs.failAttempt(job.id(), failureCode, request.message(), request.retryable());
    }

    /**
     * 문서에서 뽑은 프로젝트 정형 정보를 받는다 (S15P21A604-597, GitLab #169).
     *
     * <p>다른 네 경로와 달리 <b>성공한 Job 도 받는다.</b> 추출은 임베딩이 끝난 뒤에 오기 때문이다.
     * 버려진 Job({@code DEAD}·{@code CANCELLED})만 {@code 410} 이다 — 취소된 문서의 추출 결과가
     * 최신 값을 덮지 않아야 한다.
     *
     * <p><b>최신성은 {@code jobId} 로만 판정한다.</b> 도착 순서로 판정하면 재시도가 오래된 추출을
     * 새 값 위에 덮는다. 오래된 결과와 같은 Job 의 재전송은 둘 다 조용히 {@code 204} 다 — 보낸 쪽이
     * 할 수 있는 일이 없고, 실패로 답하면 워커가 성공한 작업을 실패로 보고한다.
     *
     * <p><b>부스에 프로젝트가 없으면 아무것도 하지 않는다.</b> 정형 정보는 프로젝트의 속성이고,
     * 프로젝트를 만들지 않은 부스에는 담을 곳이 없다. 이것도 {@code 204} 다 — 보낸 쪽이 고칠 수 있는
     * 잘못이 아니다.
     *
     * <p>두 값은 <b>함께</b> 갈아끼운다. 한 문서에서 나온 한 번의 추출이라, 한쪽만 보내 나머지를
     * 남겨 두면 서로 다른 문서에서 온 두 값이 한 프로젝트에 섞인다.
     */
    @Transactional
    public void acceptProjectFacts(long jobId, String rawBody) {
        ProjectFactsRequest request = StrictJsonReader.read(rawBody, ProjectFactsRequest.class);
        String sourceHash = required(request.sourceHash(), "sourceHash");
        String targetAudience = fact(request.targetAudience(), "targetAudience");
        String techStack = fact(request.techStack(), "techStack");
        if (targetAudience == null && techStack == null) {
            // 빈 요청이 기존 값을 지우는 것을 막는다. 정말 둘 다 없는 문서라면 보내지 않으면 된다.
            throw ApiException.fieldInvalid("targetAudience", "둘 중 하나는 값이 있어야 합니다.");
        }

        JobRow job = locked(jobId, request.attemptNo());
        if (ABANDONED.contains(job.status())) {
            throw new ApiException(ErrorCode.JOB_GONE);
        }
        if (!job.sourceHash().equals(sourceHash)) {
            // 같은 jobId 인데 다른 파일에서 뽑았다는 뜻이다. finalize 와 같은 판단이다.
            throw ApiException.fieldInvalid("sourceHash", "Job 의 원본 해시와 다릅니다.");
        }
        if (request.documentId() != null && request.documentId() != job.documentId()) {
            // 보낸 쪽이 다른 문서를 말하고 있다. 받아 주면 엉뚱한 부스의 프로젝트가 바뀐다.
            throw ApiException.fieldInvalid("documentId", "Job 의 문서와 다릅니다.");
        }

        Project project = projects.findByBoothId(job.boothId()).orElse(null);
        if (project == null || !project.factsAreOlderThan(jobId)) {
            return;
        }
        project.applyFacts(targetAudience, techStack, job.documentId(), jobId, Instant.now());
    }

    /**
     * The Job as it must be for a result to count, with the row locked for the rest of the
     * transaction.
     *
     * <p>A missing Job and a finished one are the same answer: the document was deleted (the row
     * went with it, {@code ON DELETE CASCADE}) or the Job was cancelled, succeeded or died. Nothing
     * distinguishes them for the caller — there is no attempt left to send results to.
     */
    private JobRow live(long jobId, Integer attemptNo) {
        JobRow job = locked(jobId, attemptNo);
        if (TERMINAL.contains(job.status())) {
            throw new ApiException(ErrorCode.JOB_GONE);
        }
        return job;
    }

    /**
     * The Job with its row held, and the sender proved to be the attempt that owns it.
     *
     * <p>The attempt is checked before the status so a worker that was fenced out hears
     * {@code 409} — the one answer that tells it to stop — even when the Job has since finished
     * under the attempt that replaced it.
     */
    private JobRow locked(long jobId, Integer attemptNo) {
        int attempt = nonNegative(attemptNo, "attemptNo");
        JobRow job = jobs.lockById(jobId).orElseThrow(() -> new ApiException(ErrorCode.JOB_GONE));
        if (job.attemptNo() != attempt) {
            throw new ApiException(ErrorCode.JOB_ATTEMPT_STALE,
                    "현재 attempt 는 " + job.attemptNo() + " 입니다.");
        }
        return job;
    }

    private static List<StagedChunk> staged(List<ChunkPayload> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw ApiException.fieldInvalid("chunks", "한 개 이상이어야 합니다.");
        }
        if (chunks.size() > MAX_CHUNKS_PER_BATCH) {
            throw ApiException.fieldInvalid("chunks",
                    "한 batch 는 " + MAX_CHUNKS_PER_BATCH + "개 이하여야 합니다.");
        }
        Set<Integer> seen = new HashSet<>();
        List<StagedChunk> staged = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            ChunkPayload chunk = chunks.get(index);
            if (chunk == null) {
                throw ApiException.fieldInvalid("chunks[" + index + "]", "값이 필요합니다.");
            }
            String field = "chunks[" + index + "]";
            int chunkNo = nonNegative(chunk.chunkNo(), field + ".chunkNo");
            if (!seen.add(chunkNo)) {
                // 같은 batch 안의 중복은 staging PK 가 조용히 흡수한다 — 그러면 보낸 쪽은 N 개를
                // 넣었다고 믿고 finalize 에서 개수가 어긋난다. 원인을 말할 수 있는 여기서 막는다.
                throw ApiException.fieldInvalid(field + ".chunkNo", "같은 batch 안에서 중복됩니다.");
            }
            staged.add(new StagedChunk(chunkNo, required(chunk.content(), field + ".content"),
                    EmbeddingVector.literal(chunk.embedding(), field + ".embedding"),
                    required(chunk.embeddingModelId(), field + ".embeddingModelId"),
                    chunk.pageNumber(), chunk.section()));
        }
        return staged;
    }

    private static int nonNegative(Integer value, String field) {
        if (value == null || value < 0) {
            throw ApiException.fieldInvalid(field, "0 이상의 값이어야 합니다.");
        }
        return value;
    }

    private static int positive(Integer value, String field) {
        if (value == null || value < 1) {
            throw ApiException.fieldInvalid(field, "1 이상의 값이어야 합니다.");
        }
        return value;
    }

    /** 없어도 되지만, 있다면 비어 있지 않고 길이 안이어야 한다. */
    private static String fact(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            // 빈 문자열은 "없음" 과 다르게 보이지만 담기면 똑같이 쓸모없다. 보낸 쪽이 고칠 수 있다.
            throw ApiException.fieldInvalid(field, "빈 문자열은 보낼 수 없습니다.");
        }
        if (trimmed.length() > MAX_FACT_LENGTH) {
            throw ApiException.fieldInvalid(field, MAX_FACT_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.fieldInvalid(field, "값이 필요합니다.");
        }
        return value;
    }

    public record ChunkBatchRequest(Integer attemptNo, Integer batchSeq,
                                    List<ChunkPayload> chunks) { }

    /** {@code pageNumber}·{@code section} 은 V21 이 nullable 이라 계약도 같다. */
    public record ChunkPayload(Integer chunkNo, String content, List<Double> embedding,
                               String embeddingModelId, Integer pageNumber, String section) { }

    public record FinalizeRequest(Integer attemptNo, String sourceHash, Integer totalChunkCount,
                                  String embeddingModelId) { }

    public record HeartbeatRequest(Integer attemptNo) { }

    /**
     * @param documentId 선택이다 — Job 이 이미 문서를 가리키므로 서버가 쓰는 값은 Job 쪽이다.
     *        보내면 교차 검증에 쓴다
     * @param sourceHash 티켓이 {@code sourceRevision} 이라 부른 것. 이 컨트롤러의 다른 네 경로가
     *        모두 {@code sourceHash} 라 이름을 맞췄다 (AI 파트 확인 필요)
     */
    public record ProjectFactsRequest(Integer attemptNo, Long documentId, String sourceHash,
                                      String targetAudience, String techStack) { }

    /** {@code message} 는 선택이다 — 코드가 분기의 근거이고 문장은 사람이 읽을 것이다. */
    public record FailedRequest(Integer attemptNo, String failureCode, Boolean retryable,
                                String message) { }
}
