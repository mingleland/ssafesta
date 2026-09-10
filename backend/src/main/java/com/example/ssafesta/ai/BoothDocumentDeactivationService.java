package com.example.ssafesta.ai;

import com.example.ssafesta.ai.DocumentProcessingClient.CancelRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns off a booth's AI documents when its lease expires (spec 007 FR-015 · FR-041,
 * S15P21A604-496).
 *
 * <p>Before this, {@code CANCELLED} and {@code DISABLED} were states with readers and no writer:
 * the result endpoints refuse a finished Job's callbacks by them
 * ({@code AiDocumentResultService.TERMINAL}), the document list and the quota exclude them, and the
 * V21·V24 check constraints allow them — but nothing in the codebase ever produced either value.
 * Expiry is where they come from.
 *
 * <p><b>It runs in the caller's transaction.</b> spec 007 spec.md:71 requires the lease going
 * {@code EXPIRED}, the Job going {@code CANCELLED} and the document going {@code DISABLED} to be
 * one transaction, so this deliberately carries no {@code @Transactional} of its own and no new
 * propagation: {@link com.example.ssafesta.booth.BoothLeaseService} already has one open.
 *
 * <p>Only the FastAPI cancel leaves that transaction, after it commits — see
 * {@link #cancelAfterCommit}.
 */
@Service
public class BoothDocumentDeactivationService {

    private static final Logger log = LoggerFactory.getLogger(BoothDocumentDeactivationService.class);

    /** Same vocabulary the AI side sends back on its own lease check (FR-041, GitLab #162). */
    private static final String CANCEL_REASON_CODE = "BOOTH_LEASE_EXPIRED";

    private final JdbcTemplate jdbc;
    private final DocumentProcessingClient client;

    BoothDocumentDeactivationService(JdbcTemplate jdbc, DocumentProcessingClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    /**
     * Cancels the booth's live Jobs and disables its documents.
     *
     * <p>Plain SQL rather than the two repositories that own these tables: {@code AiDocumentRepository}
     * is package-private here and {@code AiDocumentJobRepository} is package-private in
     * {@code internal.ai}, and neither has a booth-scoped query. {@code AccountDeletionService} does
     * booth-scoped writes over the same tables the same way.
     *
     * <p><b>Must be called inside a transaction.</b> Nothing here checks that — the cancel
     * registration below throws if there is none, which is the loud failure we want rather than a
     * quiet half-transition.
     *
     * @param boothId the booth whose lease has just expired
     */
    public void deactivate(long boothId) {
        // Before the UPDATE below, while the active set is still named by status — V21:94 says there
        // is no staging TTL sweeper because the terminal transitions clear it, and CANCELLED is one
        // of the three it names. Deleting first is what keeps this one statement instead of an id
        // array built from the RETURNING rows.
        jdbc.update("""
                DELETE FROM ai_document_chunk_staging
                 WHERE job_id IN (SELECT id FROM ai_document_jobs
                                   WHERE booth_id = ? AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT'))
                """, boothId);

        // The active set is exactly ix_ai_document_jobs_active_document's (V21:51-53). Terminal Jobs
        // are the audit trail of earlier attempts and are left alone.
        List<CancelRequest> live = jdbc.query("""
                UPDATE ai_document_jobs
                   SET status = 'CANCELLED', finished_at = now(), updated_at = now(),
                       worker_id = NULL, lease_expires_at = NULL, next_retry_at = NULL,
                       last_error_code = ?, last_error = '임대가 만료돼 처리를 취소했습니다.'
                 WHERE booth_id = ? AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                RETURNING id, attempt_no
                """,
                (row, index) -> new CancelRequest(row.getLong("id"), row.getInt("attempt_no")),
                CANCEL_REASON_CODE, boothId);

        // FR-041 asks for the chunks too, and spec.md:71 makes them dead weight either way: a
        // re-lease starts a new Job from QUEUED rather than reusing what this one embedded. The
        // search gate already reads processing_status = 'READY', so this frees vector storage
        // rather than fixing a leak.
        int chunks = jdbc.update("DELETE FROM ai_document_chunks WHERE booth_id = ?", boothId);

        // FAILED and EXPIRED stay as they are — they are already out of the active set, and
        // overwriting them would erase why a document is not usable.
        //
        // A QUEUED row whose upload never arrived (uploaded_at IS NULL) is left alone for a harder
        // reason: FR-026 and FR-028 reach it only through EXPIRED. AiDocumentRepository's
        // expireAbandonedGrants matches on (QUEUED, uploaded_at IS NULL) and
        // AiDocumentOriginalDeleteSweeper on EXPIRED, so DISABLED here would strand the row and the
        // bytes a late PUT may have left — the grant's own hour never fires and nothing ever deletes
        // the original. FR-015 loses nothing by the exclusion: completing it needs an active lease
        // (AiDocumentService.complete → requireActiveEditor), so it cannot reach READY either way.
        int disabled = jdbc.update("""
                UPDATE ai_documents SET processing_status = 'DISABLED', updated_at = now()
                 WHERE booth_id = ?
                   AND (processing_status IN ('PROCESSING', 'READY')
                        OR (processing_status = 'QUEUED' AND uploaded_at IS NOT NULL))
                """, boothId);

        if (live.isEmpty() && disabled == 0) {
            return;
        }
        log.info("임대 만료로 문서를 껐습니다 — boothId={}, 취소 Job={}건, DISABLED 문서={}건, 삭제 chunk={}건",
                boothId, live.size(), disabled, chunks);
        // ponytail: a Job the dispatch sweeper claimed a moment ago can still be delegated after this
        // cancel goes out — DocumentJobDispatchSweeper is deliberately not transactional, so its claim
        // and its HTTP call straddle this transition. The worker that starts then is refused at its
        // first callback (410, CANCELLED is terminal), so the cost is one wasted embedding run.
        // Closing it would mean dispatching inside a transaction or re-reading status before each
        // send, and both are dearer than the waste.
        cancelAfterCommit(live);
    }

    /**
     * Tells FastAPI to stop the attempts that were still running, once the transition is durable.
     *
     * <p>FR-041 requires the delivery <b>and</b> requires it not to decide the database's state.
     * Inside the transaction it would do both wrong: a failure would roll the transition back, and
     * the call would hold row locks for the length of its timeout. So it goes after the commit.
     *
     * <p><b>Not retried, and it stops at the first failure.</b> A worker that never hears about the
     * cancellation finishes its embedding and its callback is refused — {@code CANCELLED} is
     * terminal, so batch, heartbeat and finalize all answer {@code 410}. What is lost is the
     * compute, not the consistency, which is why GitLab #162 settled on send-once. The Jobs after a
     * failure go to the same FastAPI process, so working through them buys nothing and costs a read
     * timeout each — on the lazy re-lease path this loop runs on a member's request thread, and a
     * booth at the document limit would hold it for over a minute.
     */
    private void cancelAfterCommit(List<CancelRequest> live) {
        if (live.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (int index = 0; index < live.size(); index++) {
                    CancelRequest request = live.get(index);
                    try {
                        client.cancelProcessing(request);
                    } catch (RuntimeException failure) {
                        // Class name only, like the delegation path: the message carries the URL.
                        log.warn("문서 처리 취소 전달 실패 jobId={} attemptNo={} 원인={} — 남은 {}건은"
                                        + " 보내지 않습니다. 워커는 계속 돌지만 결과는 fencing 으로 거부됩니다.",
                                request.jobId(), request.attemptNo(),
                                failure.getClass().getSimpleName(), live.size() - index - 1);
                        return;
                    }
                }
            }
        });
    }
}
