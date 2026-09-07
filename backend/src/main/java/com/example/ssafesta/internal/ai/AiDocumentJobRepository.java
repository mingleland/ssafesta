package com.example.ssafesta.internal.ai;

import java.util.List;
import java.util.Optional;
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

    /** The first result of an attempt is the evidence that a worker actually started it. */
    void markRunning(long jobId) {
        jdbc.update("""
                UPDATE ai_document_jobs SET status = 'RUNNING', updated_at = now()
                 WHERE id = ? AND status = 'QUEUED'
                """, jobId);
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

    void markDocumentReady(long documentId) {
        jdbc.update("""
                UPDATE ai_documents SET processing_status = 'READY', updated_at = now()
                 WHERE id = ?
                """, documentId);
    }

    /** {@code chunkCount} 는 finalize 전에는 {@code null} 이다 — 재전송 판정에 쓴다. */
    record JobRow(long id, long documentId, long boothId, long agentId, String sourceHash,
                  String status, int attemptNo, Integer chunkCount) { }

    record StagedChunk(int chunkNo, String content, String embedding, String embeddingModelId,
                       Integer pageNumber, String section) { }

    record StagingSummary(int total, int distinctChunkNo, int modelCount, String modelId) { }
}
