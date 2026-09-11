package com.example.ssafesta.survey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyOptionRepository extends JpaRepository<SurveyOption, Long> {

    List<SurveyOption> findByQuestionIdInOrderByQuestionIdAscDisplayOrderAsc(List<Long> questionIds);

    /**
     * Deleted before the questions that own them, because {@code survey_options.question_id} has no
     * {@code ON DELETE} (V1) — see {@link SurveyQuestionRepository#deleteAllBySurveyId}.
     *
     * <p>An empty {@code questionIds} would render {@code IN ()}, which PostgreSQL rejects, so the
     * caller skips the call when there is nothing to delete.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SurveyOption o WHERE o.questionId IN :questionIds")
    int deleteAllByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
