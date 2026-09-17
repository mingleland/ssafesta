package com.example.ssafesta.survey;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Responses, and the two questions the edit path asks of them.
 *
 * <p>Submission itself lands in {@code S15P21A604-131}; what is needed here is only whether any
 * response exists, because that is what locks a question set (C-08, invariant I-4).
 */
public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {

    /**
     * The only identifying response projection: it belongs to the administrator-only event entrant
     * list, never to a booth survey's anonymous results (S15P21A604-742 #59).
     */
    @Query("""
            select r.id as responseId, r.respondentUserId as userId, u.nickname as nickname,
                   r.submittedAt as submittedAt
            from SurveyResponse r join User u on u.id = r.respondentUserId
            where r.surveyId = :surveyId
            order by r.submittedAt desc, r.id desc
            """)
    Page<EventEntrantProjection> findEventEntrants(@Param("surveyId") Long surveyId, Pageable pageable);

    interface EventEntrantProjection {
        Long getResponseId();
        Long getUserId();
        String getNickname();
        Instant getSubmittedAt();
    }

    /** Zero means the question set may still be replaced. */
    long countBySurveyId(Long surveyId);

    /**
     * One response per member per survey — the pre-check that turns the race into a friendly 409
     * instead of a constraint error. {@code ux_survey_responses_member} is what actually enforces
     * it (V22); this exists so the common case never reaches the constraint.
     */
    boolean existsBySurveyIdAndRespondentUserId(Long surveyId, Long respondentUserId);

    /**
     * The member's own response, for telling them they already took part (S15P21A604-621).
     *
     * <p>Separate from the {@code exists} above because the event run screen shows <i>when</i> they
     * answered, and a second round trip for that one value is what the run response exists to
     * avoid. {@code ux_survey_responses_member} guarantees the {@code Optional}.
     */
    Optional<SurveyResponse> findBySurveyIdAndRespondentUserId(Long surveyId, Long respondentUserId);

    /**
     * The same for a guest, keyed on the access token subject.
     *
     * <p>Guest tokens are minted fresh on every {@code /auth/guest} call and never refreshed, so a
     * new browser session is a different respondent. There is no way to identify someone without an
     * account any further, and accepting that is the premise of C-05.
     */
    boolean existsBySurveyIdAndRespondentGuestKey(Long surveyId, String respondentGuestKey);

    Optional<SurveyResponse> findByIdAndSurveyId(Long id, Long surveyId);

    /**
     * 부스 대시보드용 기간 집계 (spec 015 FR-003, S15P21A604-501).
     *
     * <p>게스트 응답도 센다 — 설문은 게스트에게 열려 있고(V22), 빼면 응답 현황이 실제의 일부만 된다.
     *
     * <p>{@code boothId} 로 거르면 행사 설문({@code surveyKey} 를 가진 것)은 자연히 빠진다. 그쪽은
     * 부스 소유가 아니라 {@code Survey.boothId} 가 {@code null} 이다.
     */
    @Query("""
            select count(r) from SurveyResponse r
            where r.submittedAt >= :from and r.submittedAt < :to
              and r.surveyId in (select s.id from Survey s where s.boothId = :boothId)
            """)
    long countForBoothBetween(@Param("boothId") Long boothId,
                              @Param("from") Instant from, @Param("to") Instant to);
}
