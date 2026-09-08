package com.example.ssafesta.survey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One question answered inside one response (spec 010 Key Entities — 선택지 식별자 / 텍스트 / 숫자).
 *
 * <p>Three shapes share the row: choice answers put nothing here and their picks in
 * {@code survey_answer_options}, text answers fill {@code textAnswer}, ratings fill
 * {@code ratingValue}. A skipped optional question produces <b>no row at all</b> — which is what
 * makes {@code answeredCount} differ from {@code totalResponses} without anyone tracking it (spec
 * Edge Cases: 미응답 문항은 문항별 응답 수에서 제외).
 *
 * <p>{@code survey_answer_options} is not mapped. It is a two-column join table with a composite
 * primary key and nothing else; an {@code @IdClass} for it would buy no reads that a native insert
 * and a {@code GROUP BY} do not already give. {@code ProjectRepository} treats {@code project_likes}
 * the same way.
 *
 * <p>{@code ratingValue} is {@code Short} against the {@code SMALLINT} column
 * ({@code ddl-auto: validate}).
 */
@Entity
@Table(name = "survey_answers")
public class SurveyAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "response_id", nullable = false, updatable = false)
    private Long responseId;

    /**
     * The question this answers. No {@code ON DELETE} on the FK (V1), which is why a question set
     * with responses cannot be replaced — the database enforces what {@code SURVEY_LOCKED} explains.
     */
    @Column(name = "question_id", nullable = false, updatable = false)
    private Long questionId;

    @Column(name = "text_answer", columnDefinition = "text")
    private String textAnswer;

    @Column(name = "rating_value")
    private Short ratingValue;

    protected SurveyAnswer() {
    }

    private SurveyAnswer(Long responseId, Long questionId, String textAnswer, Short ratingValue) {
        this.responseId = responseId;
        this.questionId = questionId;
        this.textAnswer = textAnswer;
        this.ratingValue = ratingValue;
    }

    /** Choice answers hold neither column — the picks are rows in {@code survey_answer_options}. */
    public static SurveyAnswer ofChoice(Long responseId, Long questionId) {
        return new SurveyAnswer(responseId, questionId, null, null);
    }

    public static SurveyAnswer ofText(Long responseId, Long questionId, String text) {
        return new SurveyAnswer(responseId, questionId, text, null);
    }

    public static SurveyAnswer ofRating(Long responseId, Long questionId, short value) {
        return new SurveyAnswer(responseId, questionId, null, value);
    }

    public Long getId() {
        return id;
    }

    public Long getResponseId() {
        return responseId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getTextAnswer() {
        return textAnswer;
    }

    public Short getRatingValue() {
        return ratingValue;
    }
}
