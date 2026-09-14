package com.example.ssafesta.ai;

import com.example.ssafesta.storage.ObjectStorage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the original bytes of documents whose retention has run out (S15P21A604-556,
 * S15P21A604-637). {@link AiDocumentExpirySweeper}'s class note says this pass does not exist yet —
 * this closes that gap.
 *
 * <p>Two kinds of row, <b>one sweep path</b> (FR-028a says so explicitly), differing only in the
 * status claimed and the column the grace is measured from:
 * <ul>
 *   <li>{@code EXPIRED}, {@code expired_at} + 24h — FR-027's recovery window has closed (FR-028).
 *       Eligibility here is <b>status and elapsed time only</b>: {@code replaced_at} (FR-027a's
 *       marker for an original pushed aside by a revision, V30 / S15P21A604-386) is deliberately
 *       not read. A replaced original has no recovery path, so leaving it out of the deletion path
 *       too would keep its bytes forever.</li>
 *   <li>{@code FAILED}, {@code updated_at} + {@link #failedOriginalGrace} — investigation and
 *       reprocessing happen inside that grace (FR-028a). Reprocessing moves the row off
 *       {@code FAILED} <i>and</i> stamps {@code updated_at}, so both legs of the compare-and-swap
 *       below drop it; a row that somehow stays {@code FAILED} with a fresh {@code updated_at}
 *       simply restarts its grace.</li>
 * </ul>
 *
 * <p>Plain {@link JdbcTemplate} throughout, the same shape as {@code GameAssetDeleteQueue}: deletion
 * happens outside any transaction (a storage round trip cannot sit inside one that also holds a row
 * lock), and there is no delete queue — instead this reads a snapshot of the columns that matter,
 * deletes by that snapshot's coordinates, and only clears {@code s3_key} if <b>every</b> one of those
 * columns is still exactly what was read. If reconcile or anything else moved the row's storage
 * coordinates in between, the clearing update touches zero rows — the object just deleted might be
 * the row's <i>old</i> location, so the new coordinates must not be cleared here. They surface again,
 * unharmed, on the next pass.
 *
 * <p><b>Zero rows updated is not "success".</b> The physical delete is already done and no later
 * {@code UPDATE} can undo it, so a recovery or reprocess landing in that gap leaves a live document
 * pointing at bytes that no longer exist. FR-028a accepts that race and requires it be surfaced as
 * {@code ERROR} rather than prevented — a shared claim/lock or a persistent delete queue is
 * explicitly out of scope, one sweep instance and a one-round-trip window not being worth a new
 * persistent structure.
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
     *
     * <p>Measured from the end of each pass's own grace, so {@code EXPIRED} still escalates at
     * {@code expired_at} + 48h exactly as before and {@code FAILED} at {@code updated_at} + 8 days.
     */
    private static final Duration STUCK_AFTER = Duration.ofHours(24);

    /**
     * Caps storage round trips per pass — same reasoning as {@code GameAssetDeleteQueue.BATCH}.
     *
     * <p>ponytail: {@code ORDER BY} the grace column always re-offers the oldest rows first, and
     * there is no attempt counter to push a stuck row to the back of the line (unlike
     * {@code GameAssetDeleteQueue}, which has one). If failures ever pile past this many rows, every
     * pass retries the same {@value #BATCH} and newer documents starve behind them — the
     * {@link #STUCK_AFTER} promotion to {@code ERROR} is the alarm for that, not a fix.
     * Upgrade path if it fires for real: order by {@code least(attempts, N)} with a small counter
     * column, the same shape {@code GameAssetDeleteQueue} already uses.
     */
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final List<Pass> passes;

    AiDocumentOriginalDeleteSweeper(JdbcTemplate jdbc, ObjectStorage storage,
            @Value("${app.ai.document.failed-original-grace:P7D}") Duration failedOriginalGrace) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.passes = List.of(new Pass("EXPIRED", "expired_at", RECOVERY_WINDOW),
                new Pass("FAILED", "updated_at", failedOriginalGrace));
    }

    /** Half-hourly by default — nothing waits on this, unlike the grant-expiry sweep FR-026 times. */
    @Scheduled(fixedDelayString = "${app.ai.document.original-delete-scan-interval:PT30M}")
    public void deleteExpiredOriginals() {
        Instant now = Instant.now();
        for (Pass pass : passes) {
            for (Snapshot snapshot : due(pass, now)) {
                deleteOne(snapshot, now);
            }
        }
    }

    private List<Snapshot> due(Pass pass, Instant now) {
        return jdbc.query("""
                SELECT id, %1$s AS grace_from, storage_provider, storage_bucket, s3_key
                  FROM ai_documents
                 WHERE processing_status = ? AND %1$s <= ? AND s3_key IS NOT NULL
                 ORDER BY %1$s
                 LIMIT ?
                """.formatted(pass.graceColumn()),
                (rs, row) -> new Snapshot(pass, rs.getLong("id"), rs.getTimestamp("grace_from").toInstant(),
                        rs.getString("storage_provider"), rs.getString("storage_bucket"),
                        rs.getString("s3_key")),
                pass.status(), Timestamp.from(now.minus(pass.grace())), BATCH);
    }

    /**
     * One row. Re-checked immediately before the physical delete, not just after — {@link #due} reads
     * up to {@value #BATCH} rows in one snapshot, and by the time this row's turn comes the earlier
     * rows in the batch may have taken real time (network deletes). A document recovered via a late
     * {@code /complete}, or reprocessed inside its FAILED grace, still believes its bytes exist; the
     * post-delete compare-and-swap below only stops the DB pointer from lying about that afterwards —
     * it cannot undelete the object. Re-checking right here shrinks the exposed window from "the whole
     * batch's duration" to "this one row's own turn", which is as tight as it gets without holding a
     * lock across the network call (the reason nothing here runs inside a transaction to begin with).
     *
     * <p>A storage failure writes nothing — the row is left exactly as it was, so the next pass
     * reconsiders it whole (FR-028: "실패 시 다음 주기에 재시도").
     */
    private void deleteOne(Snapshot snapshot, Instant now) {
        if (!stillEligible(snapshot)) {
            return; // moved since the batch was read (recovered, reprocessed, reconciled, …) — its new state is next pass's problem
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
     * of "moved" are not equally bad. Coordinates changing while the row still holds the swept status
     * is reconcile doing its job — the next pass simply re-evaluates the new coordinates (WARN). The
     * row leaving that status altogether — recovered by a late {@code /complete}, or reprocessed
     * inside its FAILED grace — with {@code s3_key} still set is different: there is no next pass for
     * it, it is not a sweep candidate any more, and the bytes this call just deleted belong to a
     * document that now believes they exist (ERROR).
     */
    private void reportPostDeleteMismatch(Snapshot snapshot, Instant now) {
        CurrentRow current = currentRow(snapshot.id());
        if (current != null && !snapshot.status().equals(current.status()) && current.objectKey() != null) {
            log.error("{} 문서 {} 원본을 지웠는데 그 사이 복구·재처리돼 있었습니다 — 더 이상 원본이 없는 "
                    + "문서를 만들었습니다. provider={} 이후 상태={}",
                    snapshot.status(), snapshot.id(), snapshot.provider(), current.status());
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
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ai_documents " + match(snapshot.pass()),
                Integer.class, coordinates(snapshot));
        return count != null && count > 0;
    }

    private int clearObjectKey(Snapshot snapshot) {
        return jdbc.update("UPDATE ai_documents SET s3_key = NULL " + match(snapshot.pass()),
                coordinates(snapshot));
    }

    /**
     * The snapshot's full coordinates, every one of them compared — never trusted. The grace column
     * is in here too, which is what makes a reprocess during the {@code FAILED} grace fall out on its
     * own: it stamps {@code updated_at}, so the row no longer matches what was read.
     */
    private static String match(Pass pass) {
        return """
                WHERE id = ? AND processing_status = ? AND %s = ?
                  AND storage_provider = ? AND storage_bucket = ? AND s3_key = ?
                """.formatted(pass.graceColumn());
    }

    private static Object[] coordinates(Snapshot snapshot) {
        return new Object[] {snapshot.id(), snapshot.status(), Timestamp.from(snapshot.graceFrom()),
                snapshot.provider(), snapshot.bucket(), snapshot.objectKey()};
    }

    /**
     * WARN normally, ERROR once the row has sat unresolved a day past the end of its own grace — the
     * same age-only threshold whether this pass's reason was a storage exception or a coordinate
     * race repeatedly missing the update. Age is the only signal there is without a retry counter,
     * and either reason left unresolved that long means an original may be permanently stuck.
     */
    private void report(Snapshot snapshot, Instant now, String reason, String cause) {
        String causeSuffix = cause == null ? "" : " 원인=" + cause;
        Duration escalateAfter = snapshot.pass().escalateAfter();
        if (snapshot.graceFrom().plus(escalateAfter).isBefore(now)) {
            log.error("{} 문서 {} 가 {}시간 넘게 해결되지 않았습니다 ({}) — 저장소에 원본이 남아 "
                    + "있을 수 있습니다. provider={}{}", snapshot.status(), snapshot.id(),
                    escalateAfter.toHours(), reason, snapshot.provider(), causeSuffix);
        } else {
            log.warn("{} 문서 {} {} provider={}{}", snapshot.status(), snapshot.id(), reason,
                    snapshot.provider(), causeSuffix);
        }
    }

    /** One sweep path's parameters: the status it claims, the column its grace runs from, and that grace. */
    private record Pass(String status, String graceColumn, Duration grace) {

        Duration escalateAfter() {
            return grace.plus(STUCK_AFTER);
        }
    }

    /** Read once, before the storage call, and compared — never trusted — when clearing {@code s3_key}. */
    private record Snapshot(Pass pass, Long id, Instant graceFrom, String provider, String bucket,
                            String objectKey) {

        String status() {
            return pass.status();
        }
    }

    private record CurrentRow(String status, String objectKey) { }
}
