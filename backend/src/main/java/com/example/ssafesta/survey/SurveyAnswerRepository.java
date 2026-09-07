package com.example.ssafesta.survey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, Long> {

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
}
