package com.example.ssafesta.ai;

import com.example.ssafesta.storage.ObjectStorage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the original bytes of {@code EXPIRED} documents once the 24-hour recovery window has
 * closed (FR-028, S15P21A604-556). {@link AiDocumentExpirySweeper}'s class note says this pass does
 * not exist yet — this closes that gap.
 *
 * <p>{@code FAILED} documents are out of scope. Reprocessing or investigating a failure may still
 * need the original, so an unconditional sweep would be wrong for them — that policy is tracked as
 * a separate open decision in {@code docs/26}.
 *
 * <p>Plain {@link JdbcTemplate} throughout, the same shape as {@code GameAssetDeleteQueue}: deletion
 * happens outside any transaction (a storage round trip cannot sit inside one that also holds a row
 * lock), and there is no delete queue — instead this reads a snapshot of the columns that matter,
 * deletes by that snapshot's coordinates, and only clears {@code s3_key} if <b>every</b> one of those
 * columns is still exactly what was read. If reconcile or anything else moved the row's storage
 * coordinates in between, the clearing update touches zero rows — the object just deleted might be
 * the row's <i>old</i> location, so the new coordinates must not be cleared here. They surface again,
 * unharmed, on the next pass.
 */
@Component
class AiDocumentOriginalDeleteSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentOriginalDeleteSweeper.class);

    /** FR-027's recovery window — the same 24 hours {@link AiDocumentService} measures completion against. */
    private static final Duration RECOVERY_WINDOW = Duration.ofHours(24);

    /**
     * No retry-count column exists here, unlike {@code GameAssetDeleteQueue.attempts}, so escalation
     * is read off age instead: a row still holding its original a full day past when deletion was
     * even allowed to start is worth a page, not another silent retry — whether the reason it is
     * still stuck is a storage failure or a coordinate race makes no difference to that judgment.
     */
    private static final Duration ESCALATE_AFTER = Duration.ofHours(48);

    /**
     * Caps storage round trips per pass — same reasoning as {@code GameAssetDeleteQueue.BATCH}.
     *
     * <p>ponytail: {@code ORDER BY expired_at} always re-offers the oldest rows first, and there is
     * no attempt counter to push a stuck row to the back of the line (unlike
     * {@code GameAssetDeleteQueue}, which has one). If failures ever pile past this many rows, every
     * pass retries the same {@value #BATCH} and newer EXPIRED documents starve behind them — the
     * 48-hour {@link #ESCALATE_AFTER} promotion to {@code ERROR} is the alarm for that, not a fix.
     * Upgrade path if it fires for real: order by {@code least(attempts, N)} with a small counter
     * column, the same shape {@code GameAssetDeleteQueue} already uses.
     */
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;

    AiDocumentOriginalDeleteSweeper(JdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** Half-hourly by default — nothing waits on this, unlike the grant-expiry sweep FR-026 times. */
    @Scheduled(fixedDelayString = "${app.ai.document.original-delete-scan-interval:PT30M}")
    public void deleteExpiredOriginals() {
        Instant now = Instant.now();
        for (Snapshot snapshot : due(now)) {
            deleteOne(snapshot, now);
        }
    }

    private List<Snapshot> due(Instant now) {
        return jdbc.query("""
                SELECT id, expired_at, storage_provider, storage_bucket, s3_key
                  FROM ai_documents
                 WHERE processing_status = 'EXPIRED' AND expired_at <= ? AND s3_key IS NOT NULL
                 ORDER BY expired_at
                 LIMIT ?
                """,
                (rs, row) -> new Snapshot(rs.getLong("id"), rs.getTimestamp("expired_at").toInstant(),
                        rs.getString("storage_provider"), rs.getString("storage_bucket"),
                        rs.getString("s3_key")),
                Timestamp.from(now.minus(RECOVERY_WINDOW)), BATCH);
    }

    /**
     * One row. Re-checked immediately before the physical delete, not just after — {@link #due} reads
     * up to {@value #BATCH} rows in one snapshot, and by the time this row's turn comes the earlier
     * rows in the batch may have taken real time (network deletes). A document recovered via a late
     * {@code /complete} in that gap still believes its bytes exist; the post-delete compare-and-swap
     * below only stops the DB pointer from lying about that afterwards — it cannot undelete the
     * object. Re-checking right here shrinks the exposed window from "the whole batch's duration" to
     * "this one row's own turn", which is as tight as it gets without holding a lock across the
     * network call (the reason nothing here runs inside a transaction to begin with).
     *
     * <p>A storage failure writes nothing — the row is left exactly as it was, so the next pass
     * reconsiders it whole (FR-028: "실패 시 다음 주기에 재시도").
     */
    private void deleteOne(Snapshot snapshot, Instant now) {
        if (!stillEligible(snapshot)) {
            return; // moved since the batch was read (recovered, reconciled, …) — its new state is next pass's problem
        }
        try {
            storage.deleteObject(snapshot.provider(), snapshot.bucket(), snapshot.objectKey());
        } catch (RuntimeException failure) {
            report(snapshot, now, "원본 삭제 실패", failure.getClass().getSimpleName());
            return;
        }
        if (clearObjectKey(snapshot) == 0) {
            reportPostDeleteMismatch(snapshot, now);
        }
    }

    /**
     * The clearing update touched nothing — the row moved between {@link #stillEligible} and here,
     * the one gap this design cannot close without holding a lock across the delete call. Two shapes
     * of "moved" are not equally bad. Coordinates changing while the row is still {@code EXPIRED} is
     * reconcile doing its job — the next pass simply re-evaluates the new coordinates (WARN). The row
     * leaving {@code EXPIRED} altogether — recovered by a late {@code /complete} — with {@code s3_key}
     * still set is different: there is no next pass for it, it is not a sweep candidate any more, and
     * the bytes this call just deleted belong to a document that now believes they exist (ERROR).
     */
    private void reportPostDeleteMismatch(Snapshot snapshot, Instant now) {
        CurrentRow current = currentRow(snapshot.id());
        if (current != null && !"EXPIRED".equals(current.status()) && current.objectKey() != null) {
            log.error("EXPIRED 문서 {} 원본을 지웠는데 그 사이 복구돼 있었습니다 — 더 이상 원본이 없는 "
                    + "문서를 만들었습니다. provider={} 복구 후 상태={}",
                    snapshot.id(), snapshot.provider(), current.status());
            return;
        }
        report(snapshot, now, "원본은 지웠지만 완료 표시는 건너뜀 — 삭제 도중 좌표가 바뀜", null);
    }

    private CurrentRow currentRow(Long id) {
        return jdbc.query("SELECT processing_status, s3_key FROM ai_documents WHERE id = ?",
                rs -> rs.next() ? new CurrentRow(rs.getString("processing_status"), rs.getString("s3_key")) : null,
                id);
    }

    private boolean stillEligible(Snapshot snapshot) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ai_documents
                 WHERE id = ? AND processing_status = 'EXPIRED' AND expired_at = ?
                   AND storage_provider = ? AND storage_bucket = ? AND s3_key = ?
                """, Integer.class, snapshot.id(), Timestamp.from(snapshot.expiredAt()), snapshot.provider(),
                snapshot.bucket(), snapshot.objectKey());
        return count != null && count > 0;
    }

    private int clearObjectKey(Snapshot snapshot) {
        return jdbc.update("""
                UPDATE ai_documents SET s3_key = NULL
                 WHERE id = ? AND processing_status = 'EXPIRED' AND expired_at = ?
                   AND storage_provider = ? AND storage_bucket = ? AND s3_key = ?
                """, snapshot.id(), Timestamp.from(snapshot.expiredAt()), snapshot.provider(),
                snapshot.bucket(), snapshot.objectKey());
    }

    /**
     * WARN normally, ERROR once the row has sat unresolved 48 hours past its recovery window — the
     * same age-only threshold whether this pass's reason was a storage exception or a coordinate
     * race repeatedly missing the update. Age is the only signal there is without a retry counter,
     * and either reason left unresolved that long means an original may be permanently stuck.
     */
    private void report(Snapshot snapshot, Instant now, String reason, String cause) {
        String causeSuffix = cause == null ? "" : " 원인=" + cause;
        if (snapshot.expiredAt().plus(ESCALATE_AFTER).isBefore(now)) {
            log.error("EXPIRED 문서 {} 가 48시간 넘게 해결되지 않았습니다 ({}) — 저장소에 원본이 남아 "
                    + "있을 수 있습니다. provider={}{}", snapshot.id(), reason, snapshot.provider(), causeSuffix);
        } else {
            log.warn("EXPIRED 문서 {} {} provider={}{}", snapshot.id(), reason, snapshot.provider(), causeSuffix);
        }
    }

    /** Read once, before the storage call, and compared — never trusted — when clearing {@code s3_key}. */
    private record Snapshot(Long id, Instant expiredAt, String provider, String bucket, String objectKey) { }

    private record CurrentRow(String status, String objectKey) { }
}
