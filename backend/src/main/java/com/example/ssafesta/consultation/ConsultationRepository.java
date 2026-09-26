package com.example.ssafesta.consultation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 상담 요청의 대기열·수락·만료 (spec 011 US1). */
public interface ConsultationRepository extends JpaRepository<Consultation, Long> {

    /** 그 부스의 대기 중 요청 — 아직 시간이 남은 것만 (C-01). */
    @Query("""
            select c from Consultation c
            where c.boothId = :boothId
              and c.status = com.example.ssafesta.consultation.ConsultationStatus.REQUESTED
              and c.requestedAt > :cutoff
            order by c.requestedAt
            """)
    List<Consultation> findQueue(@Param("boothId") Long boothId, @Param("cutoff") Instant cutoff);

    /** 이 방문자가 그 부스에 걸어 둔 대기 중 요청. */
    @Query("""
            select c from Consultation c
            where c.boothId = :boothId and c.visitorUserId = :visitorUserId
              and c.status = com.example.ssafesta.consultation.ConsultationStatus.REQUESTED
            """)
    Optional<Consultation> findPendingOf(@Param("boothId") Long boothId,
                                         @Param("visitorUserId") Long visitorUserId);

    /**
     * <b>정확히 한 명만 수락한다</b> (SC-001, FR-007).
     *
     * <p>읽고-판단하고-쓰지 않는다. 두 직원이 같은 순간에 읽으면 둘 다 통과하므로, 조건을 갱신문에
     * 담아 <b>DB 가 승자를 정하게</b> 한다. 영향 행이 0이면 이미 누가 가져갔거나 만료·취소된 것이다.
     *
     * <p>기한도 여기서 본다 — 스위퍼가 아직 {@code EXPIRED} 로 옮기지 않은 만료분을 통과시키면
     * "10분" 이 스케줄러 주기만큼 늘어난다.
     *
     * @return 갱신된 행 수 — 1이면 수락 성공, 0이면 실패
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Consultation c
            set c.status = com.example.ssafesta.consultation.ConsultationStatus.ACCEPTED,
                c.staffUserId = :staffUserId,
                c.acceptedAt = :now
            where c.id = :id
              and c.status = com.example.ssafesta.consultation.ConsultationStatus.REQUESTED
              and c.requestedAt > :cutoff
            """)
    int accept(@Param("id") Long id, @Param("staffUserId") Long staffUserId,
               @Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /**
     * 기한이 지난 대기분 — 벌크 갱신 <b>전에</b> 누구에게 알릴지 확보하려고 읽는다.
     *
     * <p>갱신은 몇 행을 옮겼는지만 알려 준다. 방문자 큐와 부스 대기열에서 카드를 내리려면
     * {@code boothId}·{@code visitorUserId} 가 필요하므로 그 전에 한 번 읽는다.
     */
    @Query("""
            select c from Consultation c
            where c.status = com.example.ssafesta.consultation.ConsultationStatus.REQUESTED
              and c.requestedAt <= :cutoff
            """)
    List<Consultation> findOverdue(@Param("cutoff") Instant cutoff);

    /**
     * 기한이 지난 대기분을 {@code EXPIRED} 로 (C-01, research R-07).
     *
     * @return 옮긴 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Consultation c
            set c.status = com.example.ssafesta.consultation.ConsultationStatus.EXPIRED
            where c.status = com.example.ssafesta.consultation.ConsultationStatus.REQUESTED
              and c.requestedAt <= :cutoff
            """)
    int expireStaleAsOf(@Param("cutoff") Instant cutoff);

    /**
     * 부스 대시보드용 기간 집계 (spec 015 FR-004, S15P21A604-501).
     *
     * <p>요청 시각 기준이다 — 한 상담이 기간 경계를 넘어 끝나도 요청한 기간에 센다. 그래야 두 기간의
     * 합이 전체와 같다. 종료 시각 기준으로 세면 아직 안 끝난 상담이 어느 기간에도 안 들어간다.
     *
     * <p>{@code ended} 는 {@code count(case ...)} 다. {@code sum} 을 쓰면 대상 행이 없을 때
     * {@code null} 이 나와 읽는 쪽이 그것을 0 으로 되돌리는 코드를 또 쓰게 된다.
     */
    @Query("""
            select new com.example.ssafesta.consultation.ConsultationCounts(
                       count(c),
                       count(case when c.status = com.example.ssafesta.consultation.ConsultationStatus.ENDED
                                  then 1 end))
            from Consultation c
            where c.boothId = :boothId and c.requestedAt >= :from and c.requestedAt < :to
            """)
    ConsultationCounts countForBoothBetween(@Param("boothId") Long boothId,
                                            @Param("from") Instant from, @Param("to") Instant to);
}
