package com.example.ssafesta.game;

import com.example.ssafesta.storage.ObjectStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Objects that outlived their row, and the job that removes them (contract §7.1).
 *
 * <p>Deleting an object is a network call and cannot be part of the transaction that drops the row.
 * Inside it, a storage failure either rolls the row deletion back — so a member cannot complete
 * withdrawal because a bucket is unreachable — or is swallowed after commit, so the object stays
 * forever with nothing left pointing at it. Writing the coordinates instead is a plain INSERT that
 * commits or fails with everything else, which is why {@link #enqueue} must be called in the same
 * transaction as the delete it covers.
 *
 * <p>The sweep leases work rather than holding it: one statement pushes rows forward and commits,
 * and only then is any storage call made. Fifty rows times one round trip inside a transaction would
 * hold locks for as long as the slowest provider takes to answer.
 *
 * <p>A crash between deleting the object and clearing its row leaves the row to be retried, and the
 * retry deletes an object that is already gone. That is a success, not an error — the idempotence
 * {@link ObjectStorage#deleteObject} promises is what makes this safe to run at least once.
 */
@Component
public class GameAssetDeleteQueue {

    private static final Logger log = LoggerFactory.getLogger(GameAssetDeleteQueue.class);

    /** Enough to drain a withdrawal's assets in a few passes without a long-running sweep. */
    private static final int BATCH = 50;

    /** Retry spacing grows with attempts and stops there — a wrong bucket never becomes right. */
    private static final int MAX_BACKOFF_MINUTES = 30;

    /**
     * When a stuck row stops being a warning and starts being a reportable fact (contract §7.1).
     *
     * <p>§7.1 is explicit that silent retries are not enough here: a queue that never empties means
     * a withdrawn member's images are still in the bucket and nobody knows. Retrying forever at
     * {@code warn} is the shape T-24 had.
     */
    private static final int ESCALATE_AFTER_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;

    public GameAssetDeleteQueue(JdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /**
     * Records one object as needing deletion. <b>Call inside the transaction that removes its row.</b>
     *
     * <p>Duplicates are dropped rather than rejected: the same coordinate queued twice is one
     * deletion, and failing here would fail the withdrawal that called it.
     */
    public void enqueue(String provider, String bucket, String objectKey) {
        jdbc.update("""
                INSERT INTO game_asset_delete_queue (provider, storage_bucket, object_key)
                VALUES (?, ?, ?)
                ON CONFLICT (provider, storage_bucket, object_key) DO NOTHING
                """, provider, bucket, objectKey);
    }

    /**
     * Deletes what is due, one object per queue row.
     *
     * <p>A minute is chosen against nothing urgent: no user waits on this, and an object that
     * survives an extra minute costs storage and nothing else.
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void sweep() {
        for (Pending pending : lease()) {
            try {
                storage.deleteObject(pending.provider(), pending.bucket(), pending.objectKey());
                jdbc.update("DELETE FROM game_asset_delete_queue WHERE id = ?", pending.id());
            } catch (RuntimeException failure) {
                // The class name, not the message: the storage adapter's messages carry endpoint and
                // response detail that this column would then hand to anyone reading the table.
                jdbc.update("UPDATE game_asset_delete_queue SET last_error = ? WHERE id = ?",
                        failure.getClass().getSimpleName(), pending.id());
                if (pending.attempts() >= ESCALATE_AFTER_ATTEMPTS) {
                    log.error("자산 객체 삭제가 {}회 실패했습니다 — 탈퇴 회원의 이미지가 저장소에 남아 있을 수 있습니다."
                                    + " id={} provider={} 원인={}",
                            pending.attempts(), pending.id(), pending.provider(),
                            failure.getClass().getSimpleName());
                } else {
                    log.warn("자산 객체 삭제 실패 attempts={} id={} provider={} 원인={}",
                            pending.attempts(), pending.id(), pending.provider(),
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Claims up to {@value #BATCH} due rows by pushing their next attempt forward.
     *
     * <p>{@code SKIP LOCKED} so a second instance takes different rows instead of waiting rather
     * than duplicating work.
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> {@link #sweep} is not in a transaction and
     * calls this directly, so the one statement below commits on its own — which is exactly the
     * lease: it is durable before any storage call, and a slow provider holds no lock. An
     * annotation here would also be inert, since a call through {@code this} never reaches the
     * proxy that would apply it.
     */
    List<Pending> lease() {
        return jdbc.query("""
                UPDATE game_asset_delete_queue
                   SET attempts = attempts + 1,
                       next_attempt_at = now() + (interval '1 minute' * least(attempts + 1, ?))
                 WHERE id IN (SELECT id FROM game_asset_delete_queue
                               WHERE next_attempt_at <= now()
                               ORDER BY next_attempt_at
                               LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, provider, storage_bucket, object_key, attempts
                """,
                (rs, row) -> new Pending(rs.getLong("id"), rs.getString("provider"),
                        rs.getString("storage_bucket"), rs.getString("object_key"),
                        rs.getInt("attempts")),
                MAX_BACKOFF_MINUTES, BATCH);
    }

    /** @param attempts including this one — {@code lease} increments before returning. */
    record Pending(long id, String provider, String bucket, String objectKey, int attempts) {
    }
}
