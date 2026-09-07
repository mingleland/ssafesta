package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.internal.ai.AiDocumentJobRepository.JobRow;
import com.example.ssafesta.internal.ai.AiDocumentJobRepository.StagedChunk;
import com.example.ssafesta.internal.ai.AiDocumentJobRepository.StagingSummary;
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
 * <p>Two operations in this slice — staged batches and the finalize that publishes them. FastAPI
 * holds no database credential, so everything a result changes happens here.
 *
 * <p>Every entry point passes the same two gates first: the {@code attemptNo} must be the one that
 * owns the Job ({@code 409} otherwise), and the Job must not be finished ({@code 410} otherwise).
 * Without the first gate a worker that lost its lease, and does not know it, overwrites the work of
 * the attempt that replaced it.
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

    private static final String SUCCEEDED = "SUCCEEDED";

    private static final Set<String> TERMINAL = Set.of(SUCCEEDED, "DEAD", "CANCELLED");

    private final AiDocumentJobRepository jobs;

    AiDocumentResultService(AiDocumentJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public void acceptBatch(long jobId, String rawBody) {
        ChunkBatchRequest request = StrictJsonReader.read(rawBody, ChunkBatchRequest.class);
        int batchSeq = nonNegative(request.batchSeq(), "batchSeq");
        List<StagedChunk> staged = staged(request.chunks());

        JobRow job = live(jobId, request.attemptNo());
        jobs.markRunning(job.id());
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
}
