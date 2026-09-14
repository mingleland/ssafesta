package com.example.ssafesta.booth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 부스 방문 기록과 집계 (S15P21A604-240). */
public interface BoothVisitRepository extends JpaRepository<BoothVisit, Long> {

    /**
     * 이 회원이 그 부스에 열어 둔 방문.
     *
     * <p>입장 신호가 두 번 오면 새 행을 만들지 않고 이것을 돌려준다 — 브릿지 이벤트는 재전송될 수
     * 있고, 그때마다 행이 늘면 방문 수가 실제보다 커진다. <b>게스트는 이 경로로 합치지 못한다</b>
     * ({@code visitor_user_id} 가 {@code null} 이라 식별자가 없다).
     */
    @Query("""
            select v from BoothVisit v
            where v.boothId = :boothId and v.visitorUserId = :visitorUserId and v.exitedAt is null
            order by v.enteredAt desc
            limit 1
            """)
    Optional<BoothVisit> findOpenVisit(@Param("boothId") Long boothId,
                                       @Param("visitorUserId") Long visitorUserId);

    /**
     * 기간 안의 집계를 <b>한 질의로</b> 읽는다.
     *
     * <p>나눠 읽을 이유가 없고, 나누면 방문 수와 평균 체류가 서로 다른 순간의 값이 된다.
     *
     * <p>평균 체류는 <b>닫힌 방문만</b> 센다. 열린 방문을 "지금까지" 로 계산하면 창을 열어 둔
     * 사람 하나가 평균을 끌어올리고, 임의의 timeout 으로 닫으면 그 값은 실제 체류가 아니라 우리가
     * 고른 상수가 된다. 대신 열린 수를 함께 돌려주어 읽는 쪽이 판단하게 한다.
     *
     * @return {@code [방문 수, 순 방문자 수(회원 기준), 평균 체류 초, 열린 방문 수]}
     */
    @Query(value = """
            select count(*),
                   count(distinct visitor_user_id),
                   coalesce(avg(extract(epoch from (exited_at - entered_at)))
                            filter (where exited_at is not null), 0),
                   count(*) filter (where exited_at is null)
            from booth_visit_events
            where booth_id = :boothId and entered_at >= :from and entered_at < :to
            """, nativeQuery = true)
    Object[] summarize(@Param("boothId") Long boothId,
                       @Param("from") Instant from, @Param("to") Instant to);
}
