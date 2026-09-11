package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Job, its staging rows and the chunk swap behind {@code /internal/ai/document-jobs/**}
 * (S15P21A604-400).
 *
 * <p>Plain JDBC, for the same reason as {@link AiChunkSearchRepository}: {@code embedding} is
 * {@code vector(1536)} and there is no pgvector Hibernate type on the classpath. The embedding
 * crosses as text and is cast in SQL.
 */
@Repository
class AiDocumentJobRepository {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentJobRepository.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    AiDocumentJobRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Reads the Job and holds it for the rest of the transaction.
     *
     * <p>{@code FOR UPDATE} is what makes the fencing check mean anything: without it two late
     * batches read the same {@code attempt_no}, both pass, and both write. The lock is taken before
     * any decision so the row cannot change between the check and the write.
     */
    Optional<JobRow> lockById(long jobId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, document_id, booth_id, agent_id, source_hash, status, attempt_no,
                           chunk_count
                      FROM ai_document_jobs
                     WHERE id = ?
                       FOR UPDATE
                    """,
                    (row, index) -> new JobRow(row.getLong("id"), row.getLong("document_id"),
                            row.getLong("booth_id"), row.getLong("agent_id"),
                            row.getString("source_hash"), row.getString("status"),
                            row.getInt("attempt_no"), row.getObject("chunk_count", Integer.class)),
                    jobId));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    /**
     * {@code ON CONFLICT DO NOTHING} on the staging PK {@code (job_id, batch_seq, chunk_no)} is the
     * whole of batch idempotency — a resent batch writes the same rows and changes nothing.
     */
    void stageChunks(long jobId, int batchSeq, List<StagedChunk> chunks) {
        jdbc.batchUpdate("""
                INSERT INTO ai_document_chunk_staging (job_id, batch_seq, chunk_no, content,
                    embedding, embedding_model_id, page_number, section)
                VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, ?)
                ON CONFLICT (job_id, batch_seq, chunk_no) DO NOTHING
                """, chunks.stream().map(chunk -> new Object[] {
                        jobId, batchSeq, chunk.chunkNo(), chunk.content(), chunk.embedding(),
                        chunk.embeddingModelId(), chunk.pageNumber(), chunk.section()
                }).toList());
    }

    /** Every fact finalize checks, in one round trip. */
    StagingSummary summarise(long jobId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)                          AS total,
                       COUNT(DISTINCT chunk_no)          AS distinct_chunk_no,
                       COUNT(DISTINCT embedding_model_id) AS model_count,
                       MIN(embedding_model_id)           AS model_id
                  FROM ai_document_chunk_staging
                 WHERE job_id = ?
                """,
                (row, index) -> new StagingSummary(row.getInt("total"),
                        row.getInt("distinct_chunk_no"), row.getInt("model_count"),
                        row.getString("model_id")),
                jobId);
    }

    /**
     * Swaps in the new chunks: the old ones go, the staged ones arrive already
     * {@code searchable = TRUE}.
     *
     * <p>Both statements run inside finalize's single transaction. A reader either sees the whole
     * previous version or the whole new one — never a document with half its chunks, which is the
     * failure {@code searchable} exists to prevent.
     */
    void replaceChunks(JobRow job) {
        jdbc.update("DELETE FROM ai_document_chunks WHERE document_id = ?", job.documentId());
        named.update("""
                INSERT INTO ai_document_chunks (document_id, booth_id, agent_id, job_id, chunk_no,
                    content, embedding, embedding_model_id, page_number, section, searchable)
                SELECT :documentId, :boothId, :agentId, :jobId, s.chunk_no,
                       s.content, s.embedding, s.embedding_model_id, s.page_number, s.section, TRUE
                  FROM ai_document_chunk_staging s
                 WHERE s.job_id = :jobId
                """, java.util.Map.of("documentId", job.documentId(), "boothId", job.boothId(),
                        "agentId", job.agentId(), "jobId", job.id()));
    }

    /**
     * The retry schedule, as one definition used by both failure paths.
     *
     * <p>1 · 5 · 15 minutes (GitLab #119, 2026-09-03 — the AI part's existing worker values). The
     * argument is the SQL expression for the <b>new</b> attempt number, because a failure of attempt
     * {@code k} schedules attempt {@code k + 1}. Only internal literals are ever interpolated.
     */
    private static final String BACKOFF = """
            CASE %s WHEN 1 THEN INTERVAL '1 minute'
                    WHEN 2 THEN INTERVAL '5 minutes'
                    ELSE INTERVAL '15 minutes' END""";

    /** 90초 (GitLab #119). heartbeat 주기(30초)보다 커야 한다는 제약은 보내는 쪽 몫이다. */
    private static final int LEASE_SECONDS = 90;

    /**
     * Pushes the lease out, and starts it if this is the attempt's first sign of life.
     *
     * <p>Called by <b>every</b> result a running worker sends, not only the heartbeat. A batch is
     * proof of life too, and if it only moved the status the Job would sit {@code RUNNING} with a
     * {@code NULL} lease — which the sweeper cannot see, because {@code NULL < now()} is not true.
     * A worker that died between its first batch and its first heartbeat would hold that document
     * forever: the partial unique index refuses a replacement Job while one is active.
     *
     * <p>{@code RETRY_WAIT} promotes as well as {@code QUEUED}, and leaving it out was the same bug
     * one layer along (S15P21A604-487). A reclaimed Job is {@code RETRY_WAIT}, so every attempt
     * after the first started from that state — the lease was taken but the status never moved, and
     * the sweeper only looks at {@code RUNNING} ({@code ix_ai_document_jobs_lease}). The second
     * worker to die was therefore never reclaimed, {@code attempt_no} froze, and the active-Job
     * index kept refusing a replacement for that document for good. Which is to say the retry path
     * had no lease protection and no fencing at all — the ordinary path, since a retry only exists
     * because the first attempt failed.
     *
     * <p>The terminal states are still preserved rather than promoted. {@code live} refuses them
     * with {@code 410} before this runs, so they cannot arrive here, but a {@code CASE} that would
     * resurrect a {@code DEAD} Job if that ever changed is not worth writing.
     */
    void extendLease(long jobId, long documentId) {
        int moved = jdbc.update("""
                UPDATE ai_document_jobs
                   SET lease_expires_at = now() + make_interval(secs => ?),
                       status = CASE WHEN status IN ('QUEUED', 'RETRY_WAIT') THEN 'RUNNING'
                                     ELSE status END,
                       updated_at = now()
                 WHERE id = ?
                """, LEASE_SECONDS, jobId);
        if (moved == 1) {
            markDocumentProcessing(documentId);
        }
    }

    /**
     * The document follows its Job into work: {@code RUNNING} here means {@code PROCESSING} there.
     *
     * <p><b>Why the first worker signal and not the delegation.</b> Spring's call to FastAPI
     * succeeding is not the work starting — {@code dispatchQuietly} swallows a failure and the
     * dispatch sweeper retries every thirty seconds, so writing {@code PROCESSING} when the request
     * goes out would show 처리 중 for a document nothing is touching while the AI service is down.
     * A batch or a heartbeat is the worker saying it has the file.
     *
     * <p><b>Only when the Job row actually moved.</b> {@code moved == 1} is also proof of the
     * status: {@code live} refuses the terminal states with {@code 410} before this runs, so a Job
     * that reaches here is {@code QUEUED}, {@code RETRY_WAIT} or {@code RUNNING} — and the
     * statement above leaves all three as {@code RUNNING}. Without the check a Job whose update
     * matched nothing could still drag its document to {@code PROCESSING}.
     *
     * <p><b>Only from {@code QUEUED}.</b> {@code EXPIRED}, {@code FAILED} and {@code DISABLED} are
     * states a late worker signal must not undo — the expiry sweep's decision, in particular, is
     * exactly what {@code finalizeDoesNotPublishAnExpiredDocument} pins. Every heartbeat after the
     * first matches nothing, which is what makes this cheap enough to run on all of them.
     */
    private void markDocumentProcessing(long documentId) {
        jdbc.update("""
                UPDATE ai_documents SET processing_status = 'PROCESSING', updated_at = now()
                 WHERE id = ? AND processing_status = 'QUEUED'
                """, documentId);
    }

    /**
     * A Job that just died takes its document with it (FR-006).
     *
     * <p>Called after the statement that may have written {@code DEAD}, and conditioned on the row
     * it wrote — the {@code DEAD}/{@code RETRY_WAIT} decision lives in that statement's
     * {@code CASE}, and reading it back is cheaper than lifting the decision into Java where it
     * would have to be kept in step with the SQL.
     *
     * <p><b>{@code RETRY_WAIT} deliberately does not reach here.</b> An attempt failing is not the
     * document failing: the next attempt is already scheduled, the document stays
     * {@code PROCESSING}, and its finalize still publishes. Marking it failed would leave a live
     * Job attached to a dead document.
     *
     * <p>{@code DEAD} is terminal for the document too, so this is safe: {@code /complete} refuses
     * a {@code FAILED} document, and re-uploading the same file starts a new row because the
     * duplicate check excludes {@code FAILED}. Nothing arrives later expecting to publish this one.
     */
    private void markDocumentFailedIfJobDead(long jobId) {
        jdbc.update("""
                UPDATE ai_documents d SET processing_status = 'FAILED', updated_at = now()
                  FROM ai_document_jobs j
                 WHERE j.id = ? AND d.id = j.document_id AND j.status = 'DEAD'
                   AND d.processing_status IN ('QUEUED', 'PROCESSING')
                """, jobId);
    }

    /**
     * Ends the attempt: another one is scheduled, or the Job dies.
     *
     * <p>{@code attempt_no} always advances, even into {@code DEAD} — that is what fences the worker
     * that just failed. A late result from it then meets {@code 409} instead of being applied.
     *
     * @param retryable whether the worker says another attempt could succeed. {@code false} skips
     *                  straight to {@code DEAD} — retrying a corrupt file only wastes the retries.
     */
    void failAttempt(long jobId, String failureCode, String message, boolean retryable) {
        jdbc.update("""
                UPDATE ai_document_jobs
                   SET attempt_no = attempt_no + 1,
                       status = CASE WHEN NOT ? OR attempt_no + 1 > max_retries
                                     THEN 'DEAD' ELSE 'RETRY_WAIT' END,
                       next_retry_at = CASE WHEN ? AND attempt_no + 1 <= max_retries
                                            THEN now() + %s END,
                       finished_at = CASE WHEN NOT ? OR attempt_no + 1 > max_retries
                                          THEN now() END,
                       last_error_code = ?, last_error = ?,
                       worker_id = NULL, lease_expires_at = NULL, updated_at = now()
                 WHERE id = ?
                """.formatted(BACKOFF.formatted("attempt_no + 1")),
                retryable, retryable, retryable, failureCode, message, jobId);
        markDocumentFailedIfJobDead(jobId);
        clearStaging(jobId);
    }

    /**
     * Takes back the Jobs whose worker stopped reporting.
     *
     * <p>The reclaim is the only thing that makes a dead worker recoverable — and the only thing
     * that stops one: without the {@code attempt_no} bump, a process that froze past its lease and
     * woke up later would still be accepted as the current attempt.
     *
     * <p>{@code SKIP LOCKED} so a second instance takes different rows instead of waiting.
     *
     * @return how many Jobs were reclaimed
     */
    int reclaimExpiredLeases(int limit) {
        List<Long> reclaimed = jdbc.queryForList("""
                UPDATE ai_document_jobs j
                   SET attempt_no = s.attempt_no + 1,
                       status = CASE WHEN s.attempt_no + 1 > s.max_retries
                                     THEN 'DEAD' ELSE 'RETRY_WAIT' END,
                       next_retry_at = CASE WHEN s.attempt_no + 1 <= s.max_retries
                                            THEN now() + %s END,
                       finished_at = CASE WHEN s.attempt_no + 1 > s.max_retries THEN now() END,
                       last_error_code = 'LEASE_EXPIRED',
                       last_error = 'heartbeat 가 끊겨 Job 을 회수했습니다.',
                       worker_id = NULL, lease_expires_at = NULL, updated_at = now()
                  FROM (SELECT id, attempt_no, max_retries
                          FROM ai_document_jobs
                         WHERE status = 'RUNNING' AND lease_expires_at < now()
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED) s
                 WHERE j.id = s.id
                RETURNING j.id
                """.formatted(BACKOFF.formatted("s.attempt_no + 1")), Long.class, limit);
        // 죽은 attempt 가 남긴 staging 은 지운다. 남기면 다음 attempt 의 batch 와 섞여
        // finalize 개수 검증이 엉뚱한 곳에서 걸린다.
        if (!reclaimed.isEmpty()) {
            jdbc.update("DELETE FROM ai_document_chunk_staging WHERE job_id = ANY (?)",
                    (PreparedStatement statement) -> statement.setArray(1,
                            statement.getConnection().createArrayOf("bigint", reclaimed.toArray())));
            // 회수분 중 재시도를 다 쓴 것만 DEAD 다. 그 문서만 따라 죽는다 — 조건은 방금 쓴
            // Job 행이고, 같은 배열을 다시 쓰므로 회수 목록을 또 만들지 않는다.
            jdbc.update("""
                    UPDATE ai_documents d SET processing_status = 'FAILED', updated_at = now()
                      FROM ai_document_jobs j
                     WHERE j.id = ANY (?) AND d.id = j.document_id AND j.status = 'DEAD'
                       AND d.processing_status IN ('QUEUED', 'PROCESSING')
                    """,
                    (PreparedStatement statement) -> statement.setArray(1,
                            statement.getConnection().createArrayOf("bigint", reclaimed.toArray())));
        }
        return reclaimed.size();
    }

    /** No TTL sweeper: staging is cleared here and on the terminal Job transitions (V21). */
    void clearStaging(long jobId) {
        jdbc.update("DELETE FROM ai_document_chunk_staging WHERE job_id = ?", jobId);
    }

    void markSucceeded(long jobId, int chunkCount) {
        jdbc.update("""
                UPDATE ai_document_jobs
                   SET status = 'SUCCEEDED', chunk_count = ?, finished_at = now(), updated_at = now()
                 WHERE id = ?
                """, chunkCount, jobId);
    }

    /**
     * Publishes the document, but only from a state that is allowed to reach {@code READY}.
     *
     * <p>Unconditional before S15P21A604-174. That was reachable once the expiry sweeper started
     * producing {@code EXPIRED} rows: a document that left the active set would be pulled back into
     * it by a finalize, silently, with no upload behind it.
     *
     * <p><b>{@code PROCESSING} only.</b> -174 had to allow {@code QUEUED} as well because nothing
     * wrote {@code PROCESSING} — every healthy finalize arrived on a {@code QUEUED} document and a
     * narrow guard would have published nothing. {@link #extendLease} now writes it, and the path
     * is closed: finalize needs {@code totalChunkCount} positive and the staged total to match it,
     * staging is only written by {@code acceptBatch}, and {@code acceptBatch} calls
     * {@code extendLease} before it stages. So a batch — and therefore {@code PROCESSING} —
     * provably precedes every finalize that gets this far.
     *
     * <p><b>A miss rolls the whole finalize back</b> (FR-040a). It used to log and let the rest
     * commit, on the grounds that redoing a successful Job is worse than a document staying put.
     * That reading was wrong about what committed: chunk replacement and Job {@code SUCCEEDED}
     * alone are exactly the partial outcome FR-040's single transaction forbids — new chunks
     * hanging off a document nothing can search, and a Job claiming it succeeded. The document was
     * never going to be published either way; the only question was whether the other half of the
     * work stayed behind as a lie.
     *
     * <p>The caller answers {@code 410 JOB_GONE}. Rolling back un-marks {@code SUCCEEDED} too, so a
     * resend cannot take finalize's idempotent branch and gets the same 410, having changed
     * nothing — which is the truth of it: no attempt of this Job can publish a document that has
     * left {@code PROCESSING}. Putting a {@code DISABLED} or {@code EXPIRED} document back is a
     * business decision belonging to FR-015 and FR-026, not to finalize (spec 007 범위 밖).
     */
    void markDocumentReady(long documentId) {
        int published = jdbc.update("""
                UPDATE ai_documents SET processing_status = 'READY', updated_at = now()
                 WHERE id = ? AND processing_status = 'PROCESSING'
                """, documentId);
        if (published == 0) {
            log.error("문서 {} 를 READY 로 올리지 못했습니다 — 허용되지 않는 상태입니다. "
                    + "finalize 전체를 롤백합니다.", documentId);
            throw new ApiException(ErrorCode.JOB_GONE);
        }
    }

    /**
     * Pushes out the document this one was uploaded to replace, now that the replacement is live
     * (FR-019 · FR-027a, S15P21A604-386).
     *
     * <p><b>Here and not at {@code /complete}.</b> Retiring the original when the upload finishes
     * would leave the agent with nothing to answer from whenever processing then fails — and
     * processing failing is the ordinary case this whole retry machinery exists for. The original
     * stays {@code READY} and searchable right up to the statement above; if finalize throws
     * anywhere, including there, this never runs and nothing moved.
     *
     * <p>The row keeps {@code EXPIRED} rather than gaining a status of its own, and
     * {@code replaced_at} is what separates it from an upload that never arrived — the recovery
     * path reads that one column (FR-027a). {@code expired_at} is set for the same clock the delete
     * sweep measures from: FR-028 treats both kinds of {@code EXPIRED} alike, and a row with no
     * recovery path that also fell out of the delete path would keep its original forever.
     *
     * <p>Only {@code QUEUED}/{@code RUNNING}/{@code RETRY_WAIT} Jobs are cancelled. A healthy
     * {@code READY} document's past Jobs are {@code SUCCEEDED} — terminal, and the audit trail of
     * how it was built.
     */
    void retireReplacedOriginal(long documentId) {
        List<Long> retired = jdbc.queryForList("""
                UPDATE ai_documents original
                   SET processing_status = 'EXPIRED', expired_at = now(), replaced_at = now(),
                       updated_at = now()
                  FROM ai_documents replacement
                 WHERE replacement.id = ? AND original.id = replacement.replaces_document_id
                   AND original.processing_status = 'READY'
                RETURNING original.id
                """, Long.class, documentId);
        if (retired.isEmpty()) {
            // The ordinary case: this document replaces nothing. (Or the original already left
            // READY — a lease expiry got there first, and DISABLED is its answer, not ours.)
            return;
        }
        long original = retired.get(0);
        // Staging first, while the active set is still named by status — the same order
        // BoothDocumentDeactivationService takes, and for the same reason: V21 has no staging TTL
        // sweeper because the terminal transitions clear it, and CANCELLED is one of them.
        jdbc.update("""
                DELETE FROM ai_document_chunk_staging
                 WHERE job_id IN (SELECT id FROM ai_document_jobs
                                   WHERE document_id = ? AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT'))
                """, original);
        jdbc.update("""
                UPDATE ai_document_jobs
                   SET status = 'CANCELLED', finished_at = now(), updated_at = now(),
                       next_retry_at = NULL, last_error_code = 'DOCUMENT_REPLACED',
                       last_error = '수정본으로 교체돼 처리를 취소했습니다.'
                 WHERE document_id = ? AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                """, original);
        // The search gate already gives nothing for a non-READY document, so this frees vector
        // storage rather than closing a leak — the same judgement the lease-expiry path records.
        jdbc.update("DELETE FROM ai_document_chunks WHERE document_id = ?", original);
        log.info("수정본 교체로 원본 문서 {} 를 물렸습니다 — 교체본 {}", original, documentId);
    }

    /** {@code chunkCount} 는 finalize 전에는 {@code null} 이다 — 재전송 판정에 쓴다. */
    record JobRow(long id, long documentId, long boothId, long agentId, String sourceHash,
                  String status, int attemptNo, Integer chunkCount) { }

    record StagedChunk(int chunkNo, String content, String embedding, String embeddingModelId,
                       Integer pageNumber, String section) { }

    record StagingSummary(int total, int distinctChunkNo, int modelCount, String modelId) { }
}
