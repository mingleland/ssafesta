package com.example.ssafesta.survey;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The aggregate reads behind {@code GET /surveys/{id}/results} (spec 010 FR-006·FR-007).
 *
 * <p><b>The server counts.</b> The frontend is never handed raw responses to add up — that is
 * FR-007, and it is also the only way SC-001 ("집계 수치가 실제 응답 데이터와 100% 일치") can be
 * checked in one place.
 *
 * <p>Computed live from the rows rather than from a counter table (C-04). A stored count is a second
 * thing that can disagree with the answers, and at festival scale — one survey per booth, responses
 * in the hundreds — each of these is one indexed {@code GROUP BY}. If that stops being true, a cache
 * goes in front of this interface and the queries stay.
 *
 * <p>Declared on {@link SurveyResponse} because it needs some managed entity; every query here names
 * its own tables. Native rather than JPQL for the same reason {@code ProjectRepository} is: the join
 * table {@code survey_answer_options} has no entity, and inventing one to count rows would be worse
 * than reading the table by name.
 */
public interface SurveyAggregateRepository extends JpaRepository<SurveyResponse, Long> {

    /**
     * Response count and the first/last submission (US2 scenario 1).
     *
     * <p>One row always — {@code count} is 0 and the two instants are {@code null} for a survey
     * nobody answered, which is what {@code totalResponses: 0} needs (FR-012).
     */
    @Query(value = """
            SELECT count(*) AS total, min(submitted_at) AS first_at, max(submitted_at) AS last_at
            FROM survey_responses WHERE survey_id = :surveyId
            """, nativeQuery = true)
    ResponseTotals totals(@Param("surveyId") Long surveyId);

    /**
     * How many responses answered each question.
     *
     * <p>Differs from {@code totalResponses} whenever someone skipped an optional question — a
     * skipped question stores no answer row at all, so this is a count of rows rather than a
     * subtraction anyone has to remember to do (spec Edge Cases).
     */
    @Query(value = """
            SELECT a.question_id AS question_id, count(*) AS answered
            FROM survey_answers a
            JOIN survey_responses r ON r.id = a.response_id
            WHERE r.survey_id = :surveyId
            GROUP BY a.question_id
            """, nativeQuery = true)
    List<QuestionAnswered> answeredCounts(@Param("surveyId") Long surveyId);

    /**
     * Picks per option.
     *
     * <p>Only options somebody chose appear here. The service fills the rest in from the question's
     * own option list so a choice with zero picks is reported as {@code 0} rather than missing —
     * an absent option and an unpopular one look identical to a chart otherwise.
     */
    @Query(value = """
            SELECT a.question_id AS question_id, ao.option_id AS option_id, count(*) AS picked
            FROM survey_answer_options ao
            JOIN survey_answers a ON a.id = ao.answer_id
            JOIN survey_responses r ON r.id = a.response_id
            WHERE r.survey_id = :surveyId
            GROUP BY a.question_id, ao.option_id
            """, nativeQuery = true)
    List<OptionPicked> optionCounts(@Param("surveyId") Long surveyId);

    /**
     * Rating distribution — one row per value that was actually given.
     *
     * <p>The average is derived from these rows in the service instead of a second {@code avg()}
     * query, so the mean and the histogram can never disagree. Values nobody chose are filled in as
     * {@code 0} across the question's whole scale (FR-006 wants 평균 <i>and</i> 분포).
     */
    @Query(value = """
            SELECT a.question_id AS question_id, a.rating_value AS rating_value, count(*) AS picked
            FROM survey_answers a
            JOIN survey_responses r ON r.id = a.response_id
            WHERE r.survey_id = :surveyId AND a.rating_value IS NOT NULL
            GROUP BY a.question_id, a.rating_value
            """, nativeQuery = true)
    List<RatingBucket> ratingBuckets(@Param("surveyId") Long surveyId);

    /**
     * Projection interfaces. Spring Data binds each getter to the alias above — {@code getQuestionId}
     * to {@code question_id} through the {@code under_score} fallback it applies to native queries
     * ({@code TupleBackedMap.FallbackTupleWrapper}).
     *
     * <p>The aliases are snake_case to match the columns. A camelCase alias binds too (the tuple
     * lookup is case-insensitive, checked by mutation), so this is a naming choice, not a rule.
     */
    interface ResponseTotals {
        long getTotal();

        Instant getFirstAt();

        Instant getLastAt();
    }

    interface QuestionAnswered {
        Long getQuestionId();

        long getAnswered();
    }

    interface OptionPicked {
        Long getQuestionId();

        Long getOptionId();

        long getPicked();
    }

    interface RatingBucket {
        Long getQuestionId();

        short getRatingValue();

        long getPicked();
    }
}
