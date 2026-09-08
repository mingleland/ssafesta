package com.example.ssafesta.survey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestion, Long> {

    /** Display order is the contract order — the API array and this list are the same sequence. */
    List<SurveyQuestion> findBySurveyIdOrderByDisplayOrderAsc(Long surveyId);

    /**
     * Whether this question belongs to this survey — the guard on the {@code questionId} filter of
     * {@code GET /surveys/{id}/text-answers}.
     *
     * <p>Without it the filter would silently return an empty page for another booth's question,
     * which reads as "nobody answered" rather than "wrong question" (계약 §8).
     */
    boolean existsByIdAndSurveyId(Long id, Long surveyId);

    /**
     * Clears the question set so a new one can take the same {@code display_order} values.
     *
     * <p>{@code flushAutomatically} sends the DELETE before the caller's INSERTs, and
     * {@code clearAutomatically} drops the now-stale entities from the persistence context so a
     * later read does not resurrect them. Without the first flag the new rows race the old ones for
     * {@code UNIQUE(survey_id, display_order)}; without the second the service can hand back
     * questions that no longer exist. {@code GameAssetRepository.deleteAllByGameId} is the same
     * shape for the same reason.
     *
     * <p>Options must be deleted first — {@code survey_options.question_id} has no
     * {@code ON DELETE}, deliberately (V1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SurveyQuestion q WHERE q.surveyId = :surveyId")
    int deleteAllBySurveyId(@Param("surveyId") Long surveyId);
}
