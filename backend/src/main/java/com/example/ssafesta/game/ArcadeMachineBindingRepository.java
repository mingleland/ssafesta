package com.example.ssafesta.game;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * 빈 자리에만 꽂는다 — 이미 누가 있으면 <b>아무것도 하지 않고</b> {@code 0} 을 돌려준다.
     *
     * <p>{@code save} 를 쓰면 안 된다. {@code machine_id} 는 부여된 기본키라 {@code save} 가
     * merge 로 돌고, 상대가 먼저 커밋한 뒤에 도착한 merge 는 INSERT 가 아니라 <b>UPDATE</b> 가
     * 된다 — 남의 자리를 조용히 덮어쓰고 기본키 위반도 나지 않는다. 기본키 위반을 잡아 409 로
     * 바꾸는 방식은 두 flush 가 겹친 인터리빙에서만 걸려서, 같은 테스트가 실행마다 통과와 실패를
     * 오갔다.
     *
     * <p>{@code ON CONFLICT DO NOTHING} 은 그 판정을 DB 한 문장에 맡긴다. 반환값이 곧 "내가
     * 잡았는가" 이고 인터리빙에 기대지 않는다.
     */
    @Modifying
    @Query(value = """
            insert into arcade_machine_bindings (machine_id, game_id, owner_user_id, created_at, updated_at)
            values (:machineId, :gameId, :ownerUserId, now(), now())
            on conflict (machine_id) do nothing
            """, nativeQuery = true)
    int insertIfFree(@Param("machineId") String machineId, @Param("gameId") Long gameId,
                     @Param("ownerUserId") Long ownerUserId);

    /**
     * 이 사람이 지금 차지한 자리 수 — 운영자 고정물은 세지 않는다 (V45 의 부분 인덱스와 같은 술어).
     */
    int countByOwnerUserId(Long ownerUserId);

    /** 이 게임이 걸린, <b>사용자가 잡은</b> 자리. 운영자 고정물은 여기 걸리지 않는다. */
    List<ArcadeMachineBinding> findByGameIdAndOwnerUserIdNotNull(Long gameId);

    /**
     * 자리를 비운다 — 게시가 내려갔거나 게임이 삭제됐다.
     *
     * <p>운영자 고정물은 남긴다. 그 행은 사람이 잡은 것이 아니라 큐레이션이고, 뒤에 걸린 게임이
     * 비공개가 됐다고 기계가 사라지면 월드에 빈 자리가 생긴다 (spec 019 FR-020 의 반대).
     */
    void deleteByGameIdAndOwnerUserIdNotNull(Long gameId);

    /**
     * Every bound machine and its game, read in <b>one</b> statement and ordered for the scene.
     *
     * <p>The same withdrawal race as {@link #findBoundGame(String)} applies to a collection: a
     * binding query followed by game lookups could combine rows from before and after the withdrawal
     * commit. Selecting the pair together gives the list one snapshot instead.
     */
    @Query("""
            select b.machineId as machineId, g as game
            from ArcadeMachineBinding b join Game g on g.id = b.gameId
            order by b.machineId asc
            """)
    List<BoundGameRow> findAllBoundGames();

    interface BoundGameRow {
        String getMachineId();

        Game getGame();
    }

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
