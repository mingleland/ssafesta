package com.example.ssafesta.booth;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoothLayoutDraftRepository extends JpaRepository<BoothLayoutDraft, Long> {

    /**
     * Saves only if nobody else has saved since the client read the draft (FR-014, invariant I-6).
     *
     * <p>The check and the write are <b>one statement</b>, so there is no window between them.
     * Reading the revision first and comparing in Java would be a check-then-act — the same shape
     * that let one member lease seven booths at once in T-110.
     *
     * @return rows updated: {@code 1} on success, {@code 0} when the revision moved on
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BoothLayoutDraft d
               SET d.schemaVersion = :schemaVersion,
                   d.layoutJson = :layoutJson,
                   d.revision = d.revision + 1,
                   d.updatedByUserId = :userId,
                   d.updatedAt = :now
             WHERE d.boothId = :boothId
               AND d.revision = :expectedRevision
            """)
    int updateIfRevisionMatches(@Param("boothId") Long boothId,
                                @Param("expectedRevision") long expectedRevision,
                                @Param("schemaVersion") int schemaVersion,
                                @Param("layoutJson") String layoutJson,
                                @Param("userId") Long userId,
                                @Param("now") Instant now);

    /**
     * Creates the first draft, and <b>only</b> if there is not one already.
     *
     * <p>{@code save()} cannot be used for this. The primary key is assigned by us, so Spring Data
     * sees a non-new entity and calls {@code merge()} — which selects first and turns into an UPDATE
     * when the row appeared in the meantime. Two concurrent first saves then both succeed and the
     * later one <b>silently overwrites</b> the earlier: exactly the loss FR-014 exists to prevent.
     * A repeated concurrency test caught it; a single run had passed by timing.
     *
     * <p>The {@code DO UPDATE … WHERE revision = 0} branch can never match — revisions start at 1 —
     * so a losing racer updates nothing and is reported as a conflict, which is what happened.
     *
     * @return {@code 1} when this call created the draft, {@code 0} when someone else got there first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO booth_layout_drafts
                   (booth_id, schema_version, layout_json, revision, updated_by_user_id, updated_at)
            VALUES (:boothId, :schemaVersion, CAST(:layoutJson AS jsonb), 1, :userId, :now)
            ON CONFLICT (booth_id) DO UPDATE
               SET revision = booth_layout_drafts.revision
             WHERE booth_layout_drafts.revision = 0
            """)
    int insertIfAbsent(@Param("boothId") Long boothId,
                       @Param("schemaVersion") int schemaVersion,
                       @Param("layoutJson") String layoutJson,
                       @Param("userId") Long userId,
                       @Param("now") Instant now);
}
