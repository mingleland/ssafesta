package com.example.ssafesta.game;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GamePublishedVersionRepository extends JpaRepository<GamePublishedVersion, Long> {

    Optional<GamePublishedVersion> findByGameIdAndVersionNo(Long gameId, int versionNo);

    /** Hard-delete only — reads every row so nothing survives the game (contracts §Persistence Boundary). */
    List<GamePublishedVersion> findAllByGameIdOrderByVersionNoDesc(Long gameId);

    /**
     * Publish history, newest first — {@code GET /games/{id}/versions} reads this.
     *
     * <p>Capped in the query, not in Java: v1 has no pagination (contracts §버전 목록), and a game with
     * hundreds of publishes should not pull every {@code project_json}-adjacent row into memory to
     * throw most of it away.
     *
     * <p>A closed projection, not the entity: {@link GamePublishedVersion#getProjectJson()} is a plain
     * {@code @Column}, so returning the entity here would fetch it anyway (up to 2MB × 50 rows) even
     * though the controller never reads it off — the field just gets thrown away after loading it into
     * the heap. {@link VersionRow} tells Spring Data to select only the three columns the contract's
     * list actually uses.
     */
    List<VersionRow> findTop50ByGameIdOrderByVersionNoDesc(Long gameId);

    /** The three columns {@code GET /games/{id}/versions} needs — {@code project_json} deliberately absent. */
    interface VersionRow {
        int getVersionNo();
        String getSchemaVersion();
        Instant getPublishedAt();
    }

    /**
     * The next publish number for this game.
     *
     * <p>Deliberately <b>not</b> "the currently published version" — that is
     * {@code games.published_version}, which can be {@code null} while this keeps counting. Mixing
     * the two is how an unpublished game would republish an old snapshot under a reused number.
     *
     * <p>{@code UNIQUE(game_id, version_no)} is what actually makes concurrent publishes safe; this
     * only picks the candidate.
     */
    @Query("SELECT COALESCE(MAX(v.versionNo), 0) FROM GamePublishedVersion v WHERE v.gameId = :gameId")
    int highestVersionNo(@Param("gameId") Long gameId);
}
