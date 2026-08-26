package com.example.ssafesta.game;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameDraftRepository extends JpaRepository<GameDraft, Long> {

    /**
     * Saves only if nobody else has saved since the client read the draft (FR-025).
     *
     * <p>The check and the write are <b>one statement</b>, so there is no window between them.
     * Reading the revision first and comparing in Java would be a check-then-act.
     *
     * @return rows updated: {@code 1} on success, {@code 0} when the revision moved on
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GameDraft d
               SET d.schemaVersion = :schemaVersion,
                   d.projectJson = :projectJson,
                   d.revision = d.revision + 1,
                   d.updatedByUserId = :userId,
                   d.updatedAt = :now
             WHERE d.gameId = :gameId
               AND d.revision = :expectedRevision
            """)
    int updateIfRevisionMatches(@Param("gameId") Long gameId,
                                @Param("expectedRevision") int expectedRevision,
                                @Param("schemaVersion") String schemaVersion,
                                @Param("projectJson") String projectJson,
                                @Param("userId") Long userId,
                                @Param("now") Instant now);

    /**
     * Creates the first draft, and <b>only</b> if there is not one already.
     *
     * <p>{@code save()} cannot be used here. The primary key is assigned by us, so Spring Data sees
     * a non-new entity and calls {@code merge()} — which selects first and turns into an UPDATE when
     * the row appeared in the meantime. Two concurrent first saves would then both succeed and the
     * later one <b>silently overwrites</b> the earlier. Spec 005 hit exactly this and a single test
     * run passed by timing; the repeated one caught it.
     *
     * <p>The {@code DO UPDATE … WHERE revision = 0} branch can never match — a created draft starts
     * at 1 — so a losing racer updates nothing and is reported as a conflict.
     *
     * @return {@code 1} when this call created the draft, {@code 0} when someone else got there first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO game_drafts
                   (game_id, schema_version, project_json, revision, updated_by_user_id, updated_at)
            VALUES (:gameId, :schemaVersion, CAST(:projectJson AS jsonb), 1, :userId, :now)
            ON CONFLICT (game_id) DO UPDATE
               SET revision = game_drafts.revision
             WHERE game_drafts.revision = 0
            """)
    int insertIfAbsent(@Param("gameId") Long gameId,
                       @Param("schemaVersion") String schemaVersion,
                       @Param("projectJson") String projectJson,
                       @Param("userId") Long userId,
                       @Param("now") Instant now);
}
