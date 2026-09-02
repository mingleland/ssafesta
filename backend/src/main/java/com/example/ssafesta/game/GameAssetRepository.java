package com.example.ssafesta.game;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Asset metadata rows. The bytes are in object storage; nothing here carries them. */
public interface GameAssetRepository extends JpaRepository<GameAsset, Long> {

    Optional<GameAsset> findByGameIdAndAssetId(Long gameId, String assetId);

    /**
     * Locks one asset row for the rest of the transaction ({@code SELECT ... FOR UPDATE}).
     *
     * <p>{@code complete} goes through this so two concurrent calls cannot both read {@code
     * UPLOADING} and both run verification. The second one waits, then sees {@code READY} and
     * returns the first one's result — which is what makes {@code complete} idempotent (§3.2) rather
     * than merely usually-idempotent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM GameAsset a WHERE a.gameId = :gameId AND a.assetId = :assetId")
    Optional<GameAsset> findForUpdate(@Param("gameId") Long gameId, @Param("assetId") String assetId);

    /** Live rows for the Draft/Publish reference snapshot — states, not bytes (decision C). */
    @Query("SELECT a FROM GameAsset a WHERE a.gameId = :gameId")
    List<GameAsset> findAllByGameId(@Param("gameId") Long gameId);

    /**
     * What counts against the per-project limit (§5, quota 결론 1).
     *
     * <p>{@code FAILED} is excluded and an expired {@code UPLOADING} stops counting: a failed upload
     * holds no bytes, so charging the limit for it would let 300 failures lock a project out of
     * uploading forever while the delete endpoint is still unbuilt.
     */
    @Query("""
            SELECT COUNT(a) FROM GameAsset a
             WHERE a.gameId = :gameId AND a.deletedAt IS NULL
               AND (a.status = com.example.ssafesta.game.GameAssetStatus.READY
                 OR (a.status = com.example.ssafesta.game.GameAssetStatus.UPLOADING
                     AND a.uploadExpiresAt > :now))
            """)
    long countChargeable(@Param("gameId") Long gameId, @Param("now") Instant now);

    /**
     * Moves the objects of rows {@link #deleteUnusable} is about to drop into the delete queue.
     *
     * <p>Must run <b>immediately before</b> that delete, in the same transaction and with the same
     * predicate. The bytes are no longer in the row, so dropping the row on its own leaves an object
     * nothing points at — and an expired grant may well have received its {@code PUT}, since the
     * browser uploads without telling us.
     *
     * <p>{@code ON CONFLICT DO NOTHING} because a coordinate already queued needs no second copy;
     * deletion is idempotent.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO game_asset_delete_queue (provider, storage_bucket, object_key)
            SELECT provider, storage_bucket, object_key FROM game_assets
             WHERE game_id = :gameId AND deleted_at IS NULL
               AND ((status = 'FAILED' AND updated_at < :threshold)
                 OR (status = 'UPLOADING' AND upload_expires_at < :threshold))
            ON CONFLICT (provider, storage_bucket, object_key) DO NOTHING
            """, nativeQuery = true)
    int enqueueUnusableObjects(@Param("gameId") Long gameId, @Param("threshold") Instant threshold);

    /**
     * Drops rows that can never become usable, so the table does not grow without bound.
     *
     * <p>Runs inside the same transaction as issuance, under the {@code games} row lock, which is why
     * no scheduler is needed and the work is bounded to one game.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM game_assets
             WHERE game_id = :gameId AND deleted_at IS NULL
               AND ((status = 'FAILED' AND updated_at < :threshold)
                 OR (status = 'UPLOADING' AND upload_expires_at < :threshold))
            """, nativeQuery = true)
    int deleteUnusable(@Param("gameId") Long gameId, @Param("threshold") Instant threshold);

    /**
     * Inserts the issued row, or reports the collision instead of throwing.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique-violation: in PostgreSQL that
     * exception marks the transaction as aborted, so the caller could not generate another id and try
     * again inside the same unit of work — which is exactly what it needs to do.
     *
     * @return 1 when the row was inserted, 0 when the {@code assetId} was already taken
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO game_assets (game_id, asset_id, kind, status,
                                     provider, storage_bucket, object_key,
                                     declared_content_type, declared_byte_size,
                                     upload_expires_at, created_by_user_id)
            VALUES (:gameId, :assetId, :kind, 'UPLOADING',
                    :provider, :storageBucket, :objectKey,
                    :declaredContentType, :declaredByteSize,
                    :uploadExpiresAt, :createdByUserId)
            ON CONFLICT (game_id, asset_id) DO NOTHING
            """, nativeQuery = true)
    int insertIssued(@Param("gameId") Long gameId, @Param("assetId") String assetId,
                     @Param("kind") String kind, @Param("provider") String provider,
                     @Param("storageBucket") String storageBucket, @Param("objectKey") String objectKey,
                     @Param("declaredContentType") String declaredContentType,
                     @Param("declaredByteSize") long declaredByteSize,
                     @Param("uploadExpiresAt") Instant uploadExpiresAt,
                     @Param("createdByUserId") Long createdByUserId);

}
