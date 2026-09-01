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

/**
 * Asset rows, and the only place that touches image bytes.
 *
 * <p>{@code content} is not mapped on {@link GameAsset}, so every read and write of it is one of the
 * statements below. That is the point: a 5 MiB column reachable from the entity would ride along on
 * the quota scan and the validator snapshot without anyone choosing it.
 */
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
     * Drops rows that can never become usable, so the table does not grow without bound.
     *
     * <p>Runs inside the same transaction as issuance, under the {@code games} row lock, which is why
     * no scheduler is needed and the work is bounded to one game. With the bytes in the same row, the
     * delete reclaims the storage too.
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
            INSERT INTO game_assets (game_id, asset_id, kind, status, provider,
                                     declared_content_type, declared_byte_size,
                                     upload_token_hash, upload_expires_at, created_by_user_id)
            VALUES (:gameId, :assetId, :kind, 'UPLOADING', 'DB',
                    :declaredContentType, :declaredByteSize,
                    :uploadTokenHash, :uploadExpiresAt, :createdByUserId)
            ON CONFLICT (game_id, asset_id) DO NOTHING
            """, nativeQuery = true)
    int insertIssued(@Param("gameId") Long gameId, @Param("assetId") String assetId,
                     @Param("kind") String kind, @Param("declaredContentType") String declaredContentType,
                     @Param("declaredByteSize") long declaredByteSize,
                     @Param("uploadTokenHash") String uploadTokenHash,
                     @Param("uploadExpiresAt") Instant uploadExpiresAt,
                     @Param("createdByUserId") Long createdByUserId);

    /**
     * Stores the uploaded bytes, but only into a row that is still waiting for them.
     *
     * <p>The {@code status} and expiry predicates are the whole overwrite defence. Once {@code
     * complete} has moved the row to {@code READY}, this matches nothing and a second {@code PUT} to
     * the same URL changes no bytes — so the metadata in the row and the object served by {@code
     * /content} cannot drift apart, which is the hazard §3.2 names for {@code FAILED} and left open
     * for {@code READY}.
     *
     * @return 1 when the bytes were stored, 0 when the row is gone, finished, or expired
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE game_assets
               SET content = :content, updated_at = now()
             WHERE game_id = :gameId AND asset_id = :assetId AND deleted_at IS NULL
               AND status = 'UPLOADING' AND upload_expires_at > now()
               AND upload_token_hash = :uploadTokenHash
            """, nativeQuery = true)
    int storeContent(@Param("gameId") Long gameId, @Param("assetId") String assetId,
                     @Param("uploadTokenHash") String uploadTokenHash, @Param("content") byte[] content);

    /** {@code null} when nothing has been uploaded yet — {@code complete} reads that as a missing PUT. */
    @Query(value = "SELECT content FROM game_assets WHERE game_id = :gameId AND asset_id = :assetId",
            nativeQuery = true)
    byte[] readContent(@Param("gameId") Long gameId, @Param("assetId") String assetId);

    /** Verification failed, so the bytes are not worth keeping — the row stays as the record of why. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE game_assets SET content = NULL WHERE game_id = :gameId AND asset_id = :assetId",
            nativeQuery = true)
    int clearContent(@Param("gameId") Long gameId, @Param("assetId") String assetId);
}
