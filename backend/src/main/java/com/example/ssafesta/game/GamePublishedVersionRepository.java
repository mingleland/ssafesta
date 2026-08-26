package com.example.ssafesta.game;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GamePublishedVersionRepository extends JpaRepository<GamePublishedVersion, Long> {

    Optional<GamePublishedVersion> findByGameIdAndVersionNo(Long gameId, int versionNo);

    /** Publish history, newest first — {@code GET /games/{id}/versions} reads this. */
    List<GamePublishedVersion> findAllByGameIdOrderByVersionNoDesc(Long gameId);

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
