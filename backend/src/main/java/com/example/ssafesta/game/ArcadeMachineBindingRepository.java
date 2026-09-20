package com.example.ssafesta.game;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Arcade machine bindings, keyed by the scene's machine id.
 *
 * <p>No finder is declared: the id <i>is</i> the lookup key, so {@code findById} is the whole
 * interface. Rows are operator seed data in v1 — there is no write endpoint yet (관리자 UI 는
 * S15P21A604-602 범위 밖).
 */
public interface ArcadeMachineBindingRepository extends JpaRepository<ArcadeMachineBinding, String> {

    /**
     * The game this machine runs, read in <b>one</b> statement.
     *
     * <p>Two reads would tear. A withdrawal deletes the binding and the game in one transaction, so
     * under {@code READ COMMITTED} a resolver that read the binding first could see it, lose the
     * race, and then find no game — answering {@code 500} for what is really "this machine is not
     * bound any more". The foreign key does not help: it forbids a dangling row at rest, not a read
     * that straddles the commit. One statement sees one snapshot, so the pair is all-or-nothing.
     *
     * <p>Empty therefore means the same thing either way — no binding, or a binding whose game went
     * with it — and both are {@code MACHINE_NOT_FOUND}.
     */
    @Query("select g from ArcadeMachineBinding b join Game g on g.id = b.gameId where b.machineId = :machineId")
    Optional<Game> findBoundGame(@Param("machineId") String machineId);

    /**
     * Every machine that runs this game, unbound — the game is about to be deleted for good.
     *
     * <p>The foreign key is deliberately plain {@code REFERENCES} with no {@code ON DELETE} (V27),
     * so whoever deletes a game has to clear this table first. Two callers do: withdrawal
     * ({@code AccountDeletionService}, in SQL) and trash eviction
     * ({@code GameLifecycleService#hardDelete}) — the second one forgot, and the delete failed with
     * the whole request rolled back (S15P21A604-681).
     *
     * <p>A machine whose game is gone is left with no row rather than a dangling one. The resolver
     * already answers {@code MACHINE_NOT_FOUND} for that, which is what an operator should see
     * until they point the machine somewhere else.
     */
    void deleteByGameId(Long gameId);
}
