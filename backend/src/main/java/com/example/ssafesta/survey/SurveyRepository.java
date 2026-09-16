package com.example.ssafesta.survey;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * One survey per booth (C-06), so the booth is the only lookup anyone needs.
 *
 * <p>{@code ux_surveys_booth} (V22) guarantees the {@code Optional} — without it two concurrent
 * saves would each read "none" and create a row, and this method would start returning whichever
 * one the planner reached first.
 */
public interface SurveyRepository extends JpaRepository<Survey, Long> {

    Optional<Survey> findByBoothId(Long boothId);

    /**
     * The festival's own survey, found by its event key rather than by a booth (V29,
     * S15P21A604-621).
     *
     * <p>{@code ux_surveys_key} guarantees the {@code Optional} the same way
     * {@code ux_surveys_booth} does for {@link #findByBoothId}.
     */
    Optional<Survey> findBySurveyKey(String surveyKey);

    /** The admin console's event-survey roster (S15P21A604-832) — every row has a {@code surveyKey}. */
    List<Survey> findBySurveyKeyIsNotNullOrderByIdAsc();

    /**
     * Which booth a survey belongs to, <b>without loading the survey</b>.
     *
     * <p>A submission needs the booth id to take the row lock that keeps an editor out, but loading
     * the {@code Survey} first would pin its fields in the persistence context at a moment before
     * that lock — and {@code rewardCoin}·{@code closesAt} are exactly what the editor may still
     * change (C-08). Reading a scalar leaves nothing cached, so the entity load after the lock sees
     * what the lock is protecting. See {@code SurveyResponseService.submit}.
     */
    @Query("SELECT s.boothId FROM Survey s WHERE s.id = :surveyId")
    Optional<Long> findBoothIdById(@Param("surveyId") Long surveyId);
}
