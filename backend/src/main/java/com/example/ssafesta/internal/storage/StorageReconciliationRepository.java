package com.example.ssafesta.internal.storage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code storage_reconciliation_log} and the one column of {@code ai_documents} a verified result
 * moves (V25, spec 007 FR-035).
 *
 * <p>Plain JDBC because {@code AiDocumentRepository} is package-private to {@code ai} and cannot be
 * reached from here — the same reason {@code AiDocumentJobRepository} writes {@code ai_documents}
 * with a {@code JdbcTemplate}. Widening that repository so one internal endpoint can use it would
 * open the entity to every caller instead.
 */
@Repository
class StorageReconciliationRepository {

    private final JdbcTemplate jdbc;

    StorageReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored result for this key, if this run has been reported for this document before. */
    Optional<StoredResult> find(String runId, long documentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT run_id, document_id, object_key, source_provider, target_provider, status,
                           apply_result, expected_size, actual_size, expected_content_type,
                           actual_content_type, expected_sha256, actual_sha256, attempt_count,
                           failure_reason, checked_at, resolved_at
                      FROM storage_reconciliation_log
                     WHERE run_id = ? AND document_id = ?
                    """,
                    (rs, row) -> new StoredResult(
                            new Payload(rs.getString("run_id"), rs.getLong("document_id"),
                                    rs.getString("object_key"), rs.getString("source_provider"),
                                    rs.getString("target_provider"), rs.getString("status"),
                                    (Long) rs.getObject("expected_size"),
                                    (Long) rs.getObject("actual_size"),
                                    rs.getString("expected_content_type"),
                                    rs.getString("actual_content_type"),
                                    rs.getString("expected_sha256"), rs.getString("actual_sha256"),
                                    rs.getInt("attempt_count"), rs.getString("failure_reason"),
                                    instant(rs.getTimestamp("checked_at")),
                                    instant(rs.getTimestamp("resolved_at"))),
                            Outcome.valueOf(rs.getString("apply_result"))),
                    runId, documentId));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    /**
     * Reads the document's storage coordinates and holds the row for the rest of the transaction.
     *
     * <p>{@code FOR UPDATE} serialises two runs that arrive for the same document at once. It does
     * not decide whether the second one is still valid — that is what the source-provider check in
     * the service is for.
     */
    Optional<DocumentRow> lockDocument(long documentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT storage_provider, storage_bucket, s3_key
                      FROM ai_documents
                     WHERE id = ?
                       FOR UPDATE
                    """,
                    (rs, row) -> new DocumentRow(rs.getString("storage_provider"),
                            rs.getString("storage_bucket"), rs.getString("s3_key")),
                    documentId));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    /**
     * Records the result, or does nothing when this key is already recorded.
     *
     * @return 1 when this call recorded it, 0 when another one already had
     */
    int insertIfAbsent(Payload payload, Outcome applyResult) {
        return jdbc.update("""
                INSERT INTO storage_reconciliation_log (
                    run_id, document_id, object_key, source_provider, target_provider, status,
                    apply_result, expected_size, actual_size, expected_content_type,
                    actual_content_type, expected_sha256, actual_sha256, attempt_count,
                    failure_reason, checked_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, document_id) DO NOTHING
                """,
                payload.runId(), payload.documentId(), payload.objectKey(), payload.sourceProvider(),
                payload.targetProvider(), payload.status(), applyResult.name(),
                payload.expectedSize(), payload.actualSize(), payload.expectedContentType(),
                payload.actualContentType(), payload.expectedSha256(), payload.actualSha256(),
                payload.attemptCount(), payload.failureReason(),
                timestamp(payload.checkedAt()), timestamp(payload.resolvedAt()));
    }

    /**
     * Moves the document to the verified location.
     *
     * <p>Provider and bucket move together: the two providers have different bucket names, so a row
     * carrying one provider's bucket with the other's name points at nothing. {@code updated_at} is
     * set by hand — there is no {@code @PreUpdate} anywhere in this codebase.
     */
    void applyStorageLocation(long documentId, String provider, String bucket) {
        jdbc.update("""
                UPDATE ai_documents SET storage_provider = ?, storage_bucket = ?, updated_at = now()
                 WHERE id = ?
                """, provider, bucket, documentId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** What happened to the document because of this result. Stored as {@code apply_result}. */
    enum Outcome {

        /** The document was moved to the verified location. */
        APPLIED,
        /** {@code MISMATCH} or {@code MISSING} — recorded, never a reason to move a document. */
        LOGGED_ONLY,
        /** Verified, but the document no longer holds that source provider and object key. */
        STALE,
        /**
         * The same {@code runId + documentId} arrived with different content. Never stored — the
         * first result stays as it is — so {@link #valueOf} on a stored value never returns this.
         */
        REPLAY_CONFLICT
    }

    /** The sixteen contract fields, normalised. Record equality is the replay comparison. */
    record Payload(String runId, long documentId, String objectKey, String sourceProvider,
                   String targetProvider, String status, Long expectedSize, Long actualSize,
                   String expectedContentType, String actualContentType, String expectedSha256,
                   String actualSha256, int attemptCount, String failureReason, Instant checkedAt,
                   Instant resolvedAt) { }

    record StoredResult(Payload payload, Outcome applyResult) { }

    /** {@code objectKey} is null once the expired-original sweep has cleared it (FR-028). */
    record DocumentRow(String provider, String bucket, String objectKey) { }
}
