package com.example.ssafesta.survey;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Responses, and the two questions the edit path asks of them.
 *
 * <p>Submission itself lands in {@code S15P21A604-131}; what is needed here is only whether any
 * response exists, because that is what locks a question set (C-08, invariant I-4).
 */
public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {

    /** Zero means the question set may still be replaced. */
    long countBySurveyId(Long surveyId);

    /**
     * One response per member per survey — the pre-check that turns the race into a friendly 409
     * instead of a constraint error. {@code ux_survey_responses_member} is what actually enforces
     * it (V22); this exists so the common case never reaches the constraint.
     */
    boolean existsBySurveyIdAndRespondentUserId(Long surveyId, Long respondentUserId);

    /**
     * The same for a guest, keyed on the access token subject.
     *
     * <p>Guest tokens are minted fresh on every {@code /auth/guest} call and never refreshed, so a
     * new browser session is a different respondent. There is no way to identify someone without an
     * account any further, and accepting that is the premise of C-05.
     */
    boolean existsBySurveyIdAndRespondentGuestKey(Long surveyId, String respondentGuestKey);

    Optional<SurveyResponse> findByIdAndSurveyId(Long id, Long surveyId);
}
