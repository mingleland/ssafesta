package com.example.ssafesta.game;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 오락기 랭킹 기록 (S15P21A604-963, GitLab #264).
 *
 * <p><b>정렬은 세 키로 완결한다</b> — {@code best_score DESC, achieved_at ASC, user_id ASC}.
 * 앞의 둘만 쓰면 동시 등록의 {@code achieved_at} 이 같은 시각으로 저장됐을 때 DB 가 둘의 순서를
 * 임의로 정하고, 그러면 {@link #findTop} 의 행 번호와 {@link #countAhead} 의 셈이 서로 다른
 * 답을 낸다. 두 질의와 인덱스({@code ix_arcade_score_records_rank})가 같은 순서를 쓴다.
 */
public interface ArcadeScoreRecordRepository extends JpaRepository<ArcadeScoreRecord, ArcadeScoreRecord.Key> {

    /**
     * 더 높은 점수일 때만 갱신한다 — <b>한 문장</b>이고 잠금이 없다.
     *
     * <p>읽고 나서 쓰면 그 사이에 같은 사람의 다른 요청이 낀다. 두 요청이 같은 기존 최고점을 읽고
     * 각자 자기 점수를 쓰면 나중에 도착한 낮은 점수가 높은 점수를 덮는다 — 기본키는 행이 하나인
     * 것만 지키지 값이 내려가는 것을 막지 않는다. {@code ON CONFLICT ... WHERE} 는 그 판정을 DB
     * 한 문장에 맡긴다. {@link ArcadeMachineBindingRepository#insertIfFree} 와 같은 방식이다.
     *
     * @return 1 이면 기록이 갱신됐고, 0 이면 기존 최고점이 더 높거나 같아 아무 일도 없었다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into arcade_score_records (machine_id, user_id, best_score, achieved_at)
            values (:machineId, :userId, :score, now())
            on conflict (machine_id, user_id) do update
              set best_score = excluded.best_score, achieved_at = excluded.achieved_at
              where arcade_score_records.best_score < excluded.best_score
            """, nativeQuery = true)
    int upsertIfHigher(@Param("machineId") String machineId, @Param("userId") Long userId,
                       @Param("score") int score);

    Optional<ArcadeScoreRecord> findByMachineIdAndUserId(String machineId, Long userId);

    /** 이 오락기의 상위 기록. {@code Pageable} 이 상한을 준다 (#264 — 최대 5명). */
    @Query("""
            select u.nickname as nickname, r.bestScore as bestScore, r.achievedAt as achievedAt
            from ArcadeScoreRecord r join User u on u.id = r.userId
            where r.machineId = :machineId
            order by r.bestScore desc, r.achievedAt asc, r.userId asc
            """)
    List<ScoreRow> findTop(@Param("machineId") String machineId, Pageable limit);

    /**
     * 이 기록보다 앞선 사람 수 — 여기에 1 을 더한 것이 순위다.
     *
     * <p>술어가 {@link #findTop} 의 {@code order by} 세 키를 그대로 뒤집은 것이다. 하나라도
     * 빠지면 두 응답의 순위가 어긋난다.
     */
    @Query("""
            select count(r) from ArcadeScoreRecord r
            where r.machineId = :machineId
              and (r.bestScore > :score
                or (r.bestScore = :score and r.achievedAt < :achievedAt)
                or (r.bestScore = :score and r.achievedAt = :achievedAt and r.userId < :userId))
            """)
    long countAhead(@Param("machineId") String machineId, @Param("score") int score,
                    @Param("achievedAt") Instant achievedAt, @Param("userId") Long userId);

    interface ScoreRow {
        String getNickname();

        int getBestScore();

        Instant getAchievedAt();
    }
}
