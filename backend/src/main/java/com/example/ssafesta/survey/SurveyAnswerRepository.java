package com.example.ssafesta.survey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, Long> {

    /** One response's answers, in question order — the admin response-detail view (S15P21A604-832). */
    List<SurveyAnswer> findByResponseIdOrderByQuestionIdAsc(Long responseId);

    /**
     * The chosen option labels for a set of choice answers, one row per pick.
     *
     * <p>{@code survey_answer_options} has no entity (see the class javadoc), so this reads it by
     * name the same way {@link #insertSelectedOption} writes it.
     */
    @Query(value = """
            SELECT ao.answer_id AS answer_id, so.option_text AS option_text
            FROM survey_answer_options ao
            JOIN survey_options so ON so.id = ao.option_id
            WHERE ao.answer_id IN :answerIds
            """, nativeQuery = true)
    List<SelectedOptionRow> findSelectedOptionLabels(@Param("answerIds") List<Long> answerIds);

    interface SelectedOptionRow {
        Long getAnswerId();

        String getOptionText();
    }

    /**
     * Records one pick on a choice answer.
     *
     * <p>{@code survey_answer_options} is a two-column join table with a composite primary key and
     * nothing else, so there is no entity to save — an {@code @IdClass} for it would buy no read
     * that this insert and a {@code GROUP BY} do not already give. {@code ProjectRepository} treats
     * {@code project_likes} the same way.
     *
     * <p>The option is validated against the question before this runs, so
     * {@code survey_answer_options_option_id_fkey} firing here would mean the question set changed
     * underneath — which the booth row lock is there to prevent.
     */
    @Modifying
    @Query(value = "INSERT INTO survey_answer_options (answer_id, option_id) VALUES (:answerId, :optionId)",
            nativeQuery = true)
    void insertSelectedOption(@Param("answerId") Long answerId, @Param("optionId") Long optionId);

    /**
     * One page of free-text answers, oldest first (FR-008, C-09).
     *
     * <p><b>Ordered by answer id ascending, and that is the contract.</b> New answers only ever get
     * larger ids, so a submission arriving while someone pages through cannot shift a row onto a
     * page they already read — which is what makes "경계 중복·누락 0건" true rather than hopeful.
     * Ordering by {@code submitted_at} would not: two answers can share a timestamp.
     *
     * <p>{@code responseId} rides along so the answers of one submitter can be grouped later. That
     * is the key spec C-03 (지원서 제출자별 상세 조회) will need, and it is not an identity — no
     * respondent field leaves this API (FR-009).
     *
     * @param questionId narrows to one question; {@code null} returns all three text types
     * @param offset {@code long} because {@code page * size} overflows an {@code int} for a large
     *               page, and a negative OFFSET is a database error rather than an empty page
     */
    @Query(value = """
            SELECT a.response_id AS response_id, a.question_id AS question_id, a.text_answer AS text
            FROM survey_answers a
            JOIN survey_responses r ON r.id = a.response_id
            WHERE r.survey_id = :surveyId AND a.text_answer IS NOT NULL
              AND (:questionId IS NULL OR a.question_id = :questionId)
            ORDER BY a.id ASC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<TextAnswerRow> findTextAnswers(@Param("surveyId") Long surveyId,
                                        @Param("questionId") Long questionId,
                                        @Param("size") int size,
                                        @Param("offset") long offset);

    @Query(value = """
            SELECT count(*)
            FROM survey_answers a
            JOIN survey_responses r ON r.id = a.response_id
            WHERE r.survey_id = :surveyId AND a.text_answer IS NOT NULL
              AND (:questionId IS NULL OR a.question_id = :questionId)
            """, nativeQuery = true)
    long countTextAnswers(@Param("surveyId") Long surveyId, @Param("questionId") Long questionId);

    interface TextAnswerRow {
        Long getResponseId();

        Long getQuestionId();

        String getText();
    }
}
