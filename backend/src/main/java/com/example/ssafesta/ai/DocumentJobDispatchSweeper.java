package com.example.ssafesta.ai;

import com.example.ssafesta.ai.DocumentProcessingClient.ProcessingRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends Jobs FastAPI has not been told about yet (S15P21A604-175).
 *
 * <p>Two kinds arrive here. A {@code QUEUED} Job whose immediate call after upload failed — or
 * whose process died between the commit and the call — and a {@code RETRY_WAIT} Job whose previous
 * attempt failed and whose next one is now due. Both need the same thing: one delegation call
 * carrying the Job's current {@code attempt_no}.
 *
 * <p>Without this a failed first call would leave a Job in {@code QUEUED} that nothing ever sends,
 * and that is not a stalled document but a <b>permanently</b> stalled one:
 * {@code ix_ai_document_jobs_active_document} counts {@code QUEUED} as active, so its document can
 * never get a replacement Job and re-uploading the file cannot clear it. S15P21A604-487 was the
 * same shape one layer along, and it is not worth learning twice.
 *
 * <p>A resend is safe by contract — the same {@code jobId + attemptNo} is accepted idempotently
 * without starting a second worker ({@code document-processing-api.yaml}). This therefore never has
 * to know whether an earlier call was delivered, which is the only reason it can be this simple.
 */
@Component
public class DocumentJobDispatchSweeper {

    private static final Logger log = LoggerFactory.getLogger(DocumentJobDispatchSweeper.class);

    /** One pass takes at most this many, so a backlog drains over several passes, not one. */
    private static final int BATCH = 50;

    /** Retry spacing grows with the Job's age and stops here — an unreachable service stays that way. */
    private static final int MAX_BACKOFF_MINUTES = 15;

    /**
     * When a Job that will not go out stops being a warning and becomes a reportable fact.
     *
     * <p>Half an hour of failed delegation is an outage, not a blip, and the document is invisible
     * to search for the whole of it. Retrying forever at {@code warn} is the shape T-24 had.
     */
    private static final int ESCALATE_AFTER_MINUTES = 30;

    private final JdbcTemplate jdbc;
    private final DocumentProcessingClient client;

    DocumentJobDispatchSweeper(JdbcTemplate jdbc, DocumentProcessingClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    /**
     * Claims the Jobs whose delivery is due, then sends them.
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> The claim commits on its own before any
     * HTTP call is made, which is what stops a slow FastAPI from holding row locks for the length of
     * its timeout. {@code GameAssetDeleteQueue.sweep} makes the same choice for the same reason, and
     * an annotation here would also be inert since {@link #claim} is called through {@code this}.
     */
    @Scheduled(fixedDelayString = "PT30S")
    public void dispatchDueJobs() {
        List<Due> due = claim();
        if (due.isEmpty()) {
            return;
        }
        for (Due job : due) {
            try {
                client.startProcessing(job.request());
            } catch (RuntimeException failure) {
                if (job.undeliveredMinutes() >= ESCALATE_AFTER_MINUTES) {
                    log.error("문서 처리 위임이 {}분째 실패하고 있습니다 — 이 문서는 검색에 들어가지 못합니다."
                                    + " jobId={} documentId={} 원인={}",
                            job.undeliveredMinutes(), job.request().jobId(),
                            job.request().documentId(), failure.getClass().getSimpleName());
                } else {
                    log.warn("문서 처리 위임 재시도 실패 jobId={} documentId={} 원인={}",
                            job.request().jobId(), job.request().documentId(),
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Pushes the next attempt forward and returns what to send, in one committed statement.
     *
     * <p>{@code attempt_no} is <b>not</b> touched. It fences the result endpoints, so bumping it
     * here would make every callback from a worker that <i>did</i> receive the earlier call answer
     * 409 — turning a delivery this method cannot confirm into a guaranteed failure.
     *
     * <p>The backoff is measured from {@code created_at} rather than a counter column: the Job's age
     * is already the number of times this has failed, near enough, and {@code created_at} is the one
     * timestamp the update does not move — so it is also safe to read back in {@code RETURNING},
     * where every other column reflects the new row.
     *
     * <p>{@code SKIP LOCKED} so a second instance takes different rows rather than waiting, and the
     * pushed-forward {@code next_retry_at} is itself the lease: a row claimed here is not due again
     * until the backoff elapses, whether or not this pass manages to send it.
     *
     * <p>A delivered Job leaves {@code QUEUED} as soon as FastAPI's first batch or heartbeat calls
     * {@code extendLease}, so it stops matching with no extra bookkeeping.
     */
    List<Due> claim() {
        return jdbc.query("""
                UPDATE ai_document_jobs
                   SET next_retry_at = now() + (interval '1 minute' * least(greatest(
                           floor(extract(epoch FROM (now() - created_at)) / 60), 1), ?)),
                       updated_at = now()
                 WHERE id IN (SELECT id FROM ai_document_jobs
                               WHERE status IN ('QUEUED', 'RETRY_WAIT')
                                 AND next_retry_at IS NOT NULL
                                 AND next_retry_at <= now()
                               ORDER BY next_retry_at
                               LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, attempt_no, document_id, booth_id, agent_id, original_filename,
                          content_type, file_size_bytes, storage_provider, storage_bucket,
                          object_key, source_hash,
                          floor(extract(epoch FROM (now() - created_at)) / 60)::int
                              AS undelivered_minutes
                """,
                (rs, row) -> new Due(new ProcessingRequest(rs.getLong("id"),
                        rs.getInt("attempt_no"), rs.getLong("document_id"), rs.getLong("booth_id"),
                        rs.getLong("agent_id"), rs.getString("original_filename"),
                        rs.getString("content_type"), rs.getLong("file_size_bytes"),
                        rs.getString("storage_provider"), rs.getString("storage_bucket"),
                        rs.getString("object_key"), rs.getString("source_hash")),
                        rs.getInt("undelivered_minutes")),
                MAX_BACKOFF_MINUTES, BATCH);
    }

    /** @param undeliveredMinutes the Job's age, which is how long delegation has been failing */
    record Due(ProcessingRequest request, int undeliveredMinutes) { }
}
