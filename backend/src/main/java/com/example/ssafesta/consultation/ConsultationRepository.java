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
}
