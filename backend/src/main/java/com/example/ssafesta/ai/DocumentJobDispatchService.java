package com.example.ssafesta.ai;

import com.example.ssafesta.ai.DocumentProcessingClient.ProcessingRequest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Creates the Job a completed document needs, and hands it to FastAPI (S15P21A604-175).
 *
 * <p>Order is the whole of it: <b>the Job row is committed before the call goes out.</b> The result
 * endpoints fence on {@code jobId + attemptNo} ({@code document-result-contract.md}), so a worker
 * that started before the row existed would have nothing to report to and every callback would be
 * stale. The reverse order is the one failure this service exists to make impossible.
 *
 * <p>Job creation belongs inside the caller's transaction — {@code AiDocumentService.settle} holds
 * the document row locked, which is what makes "one active Job per document" true before the
 * partial unique index has to enforce it. The delegation call must be outside that transaction, so
 * this class does the two halves in two methods and lets the caller place the boundary.
 */
@Service
public class DocumentJobDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentJobDispatchService.class);

    /**
     * How long a freshly created Job waits before the sweeper considers it undelivered.
     *
     * <p>The immediate call happens within milliseconds of the commit, and a delivered Job leaves
     * {@code QUEUED} as soon as FastAPI sends its first batch or heartbeat. So this only has to
     * outlast the round trip plus the worker's startup — long enough that a healthy delivery is
     * never resent, short enough that a lost one is not stuck for long.
     */
    static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final DocumentProcessingClient client;

    DocumentJobDispatchService(JdbcTemplate jdbc, DocumentProcessingClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    /**
     * Inserts the {@code QUEUED} Job for a document whose bytes have just been verified.
     *
     * <p><b>Call inside the transaction that holds the document row.</b> Every column comes from
     * that row, so the Job is a snapshot of the document as it was when the upload was accepted —
     * a later reconcile that moves the object does not silently change what FastAPI was asked to
     * fetch.
     *
     * <p>No {@code ON CONFLICT}. {@code ix_ai_document_jobs_active_document} can only fire here if
     * something created a Job for this document without the row lock, and that is a defect worth
     * seeing rather than a case to absorb — the same judgement {@code issueUploadUrl} records about
     * {@code ux_ai_documents_agent_active_sha}.
     *
     * @return the request to send once the transaction has committed
     */
    ProcessingRequest createQueuedJob(AiDocument document) {
        Long jobId = jdbc.queryForObject("""
                INSERT INTO ai_document_jobs (document_id, booth_id, agent_id, source_hash,
                        original_filename, content_type, file_size_bytes, storage_provider,
                        storage_bucket, object_key, status, next_retry_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', now() + make_interval(secs => ?))
                RETURNING id
                """, Long.class,
                document.getId(), document.getBoothId(), document.getAgentId(),
                document.getContentSha256(), document.getOriginalFilename(),
                document.getContentType(), document.getSizeBytes(), document.getStorageProvider(),
                document.getStorageBucket(), document.getObjectKey(),
                FIRST_RETRY_DELAY.toSeconds());
        // attempt_no defaults to 0 and nothing has bumped it yet, so this first delegation is
        // attempt 0 — the value the result endpoints will accept until a failure or a lease
        // reclaim moves it on.
        return new ProcessingRequest(jobId, 0, document.getId(), document.getBoothId(),
                document.getAgentId(), document.getOriginalFilename(), document.getContentType(),
                document.getSizeBytes(), document.getStorageProvider(),
                document.getStorageBucket(), document.getObjectKey(), document.getContentSha256());
    }

    /**
     * Sends the request, and lets a failure stand as a log line rather than an error.
     *
     * <p>The person who finished the upload gets their normal response either way. Their request
     * succeeded — the bytes are stored and the Job is committed — and failing it because a
     * <i>different</i> service is unreachable would make them re-upload a file that is already
     * there. {@code DailyCoinGrantInterceptor} makes the same call for the same reason.
     *
     * <p>What must not happen is failing quietly (T-24), so the refusal is an {@code ERROR}: the
     * Job is invisible to the user until FastAPI answers, and the sweeper's retries would otherwise
     * absorb a permanent outage without anyone noticing.
     */
    void dispatchQuietly(ProcessingRequest request) {
        try {
            client.startProcessing(request);
        } catch (RuntimeException failure) {
            log.error("문서 처리 위임에 실패했습니다 — Job 은 남아 있고 재배차를 기다립니다."
                            + " jobId={} documentId={} 원인={}",
                    request.jobId(), request.documentId(), failure.getClass().getSimpleName());
        }
    }
}
