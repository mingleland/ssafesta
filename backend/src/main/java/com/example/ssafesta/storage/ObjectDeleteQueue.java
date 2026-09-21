package com.example.ssafesta.storage;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p>이 큐는 한 도메인의 것이 아니다 — 게임 Asset·AI 문서·탈퇴 정리, 그리고 프로젝트 로고
 * (GitLab #241) 가 같은 줄을 쓴다. 클래스가 {@code storage} 로 옮겨온 이유가 그것이고, 테이블 이름은
 * 아직 {@code game_asset_delete_queue} 다 — 이름만 바꾸는 마이그레이션은 동작을 바꾸지 않으면서 네
 * 도메인의 SQL 을 동시에 건드려야 해서 지금 하지 않았다.
 *
 * <p>도메인별 "무엇을 지워야 하는가" 판정은 {@link StorageSweepTask} 구현이 갖는다. 여기에 두면
 * 저장소 코드가 각 도메인의 스키마를 알게 된다.
 *
 * <p>A crash between deleting the object and clearing its row leaves the row to be retried, and the
 * retry deletes an object that is already gone. That is a success, not an error — the idempotence
 * {@link ObjectStorage#deleteObject} promises is what makes this safe to run at least once.
 */
@Component
public class ObjectDeleteQueue {

    private static final Logger log = LoggerFactory.getLogger(ObjectDeleteQueue.class);

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
    /**
     * 지연 조회다. 정리 작업들은 이 큐에 좌표를 넣으므로 서로를 생성자에서 요구하면 순환이 된다 —
     * 실행 시점에 찾으면 그 순환이 사라지고, 나중에 추가되는 도메인도 이 클래스를 안 고친다.
     */
    private final ObjectProvider<StorageSweepTask> sweepTasks;

    public ObjectDeleteQueue(JdbcTemplate jdbc, ObjectStorage storage,
                             ObjectProvider<StorageSweepTask> sweepTasks) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.sweepTasks = sweepTasks;
    }

    /**
     * Records one object as needing deletion. <b>Call inside the transaction that removes its row.</b>
     *
     * <p>Duplicates are dropped rather than rejected: the same coordinate queued twice is one
     * deletion, and failing here would fail the withdrawal that called it.
     */
    public void enqueue(String provider, String bucket, String objectKey) {
        if (objectKey == null || bucket == null || provider == null) {
            // 좌표 없는 행은 저장소에 지울 것이 없다는 뜻이다. DB 제약(NOT NULL)에 맡기면 호출자의
            // 트랜잭션이 500 으로 죽으므로 여기서 이름을 붙여 막는다 (S15P21A604-939).
            throw new IllegalArgumentException(
                    "delete queue needs provider, bucket and objectKey — got provider=" + provider
                            + " bucket=" + bucket + " objectKey=" + (objectKey == null ? "null" : "<set>"));
        }
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
        // 도메인 정리가 먼저다 — 이번 회차에 새로 큐에 들어온 좌표까지 같은 실행에서 지운다.
        // 하나가 던져도 나머지와 큐 드레인은 계속한다: 한 도메인의 실패가 다른 쪽의 객체를 남기는
        // 이유가 되면 안 된다.
        for (StorageSweepTask task : sweepTasks.orderedStream().toList()) {
            try {
                task.sweep();
            } catch (RuntimeException failure) {
                log.warn("저장소 정리 작업 실패 task={} 원인={}", task.getClass().getSimpleName(),
                        failure.getClass().getSimpleName());
            }
        }
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
                    log.error("객체 삭제가 {}회 실패했습니다 — 지운 이미지나 문서가 저장소에 남아 있을 수 있습니다."
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
