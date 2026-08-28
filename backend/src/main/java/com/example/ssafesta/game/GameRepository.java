package com.example.ssafesta.game;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<Game, Long> {

    /**
     * The owner's games, live ones first, each group newest-first (contracts §내 게임 목록).
     *
     * <p>Soft-deleted games are <b>included</b>. Restoring one needs a way to see it, and adding a
     * {@code deleted} parameter or a second endpoint would only move that need somewhere else. The
     * two caps (20 live, 5 deleted) keep this list bounded, which is why v1 has no pagination.
     */
    @Query("""
            SELECT g FROM Game g
             WHERE g.ownerUserId = :ownerUserId
             ORDER BY CASE WHEN g.deletedAt IS NULL THEN 0 ELSE 1 END ASC, g.updatedAt DESC
            """)
    List<Game> findAllOwnedOrderByLiveThenUpdated(@Param("ownerUserId") Long ownerUserId);

    /** Counts against the live cap (default 20). Soft-deleted games are excluded on purpose. */
    @Query("SELECT COUNT(g) FROM Game g WHERE g.ownerUserId = :ownerUserId AND g.deletedAt IS NULL")
    long countLiveByOwner(@Param("ownerUserId") Long ownerUserId);

    /** Counts against the recycle-bin cap (default 5). */
    @Query("SELECT COUNT(g) FROM Game g WHERE g.ownerUserId = :ownerUserId AND g.deletedAt IS NOT NULL")
    long countDeletedByOwner(@Param("ownerUserId") Long ownerUserId);

    /**
     * The oldest deleted game of this owner — the one a sixth deletion pushes out.
     *
     * <p>Ordering by {@code deletedAt} rather than {@code id}: what the cap keeps is the most
     * recently discarded work, not the most recently created.
     */
    @Query("""
            SELECT g FROM Game g
             WHERE g.ownerUserId = :ownerUserId AND g.deletedAt IS NOT NULL
             ORDER BY g.deletedAt ASC
             LIMIT 1
            """)
    Optional<Game> findOldestDeletedByOwner(@Param("ownerUserId") Long ownerUserId);

    /** Hard-delete support: withdrawal and recycle-bin overflow remove the row itself. */
    @Query("SELECT g.deletedAt FROM Game g WHERE g.id = :gameId")
    Optional<Instant> findDeletedAt(@Param("gameId") Long gameId);
}
