package com.example.ssafesta.survey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One question in a survey (spec 010 FR-002).
 *
 * <p>Held by a plain {@code surveyId} rather than a {@code @OneToMany} on {@link Survey}. A
 * cascading collection would decide the order of the DELETEs and INSERTs that replacing a question
 * set produces, and Hibernate schedules cascaded child deletes <i>after</i> inserts — so a new
 * question can hit {@code UNIQUE(survey_id, display_order)} while the old one still holds the slot,
 * or the old question's DELETE can fire while its options still reference it. Replacement therefore
 * goes through explicit bulk deletes; see {@link SurveyQuestionRepository}. No entity in this
 * codebase maps {@code @OneToMany}, for the same class of reason.
 *
 * <p>{@code displayOrder} is {@code short} because the column is {@code SMALLINT} and
 * {@code ddl-auto: validate} refuses an {@code Integer} against it — {@code BoothSlot.floorNo}
 * learned that already.
 *
 * <p>{@code ratingMin}/{@code ratingMax} arrived in V22. V1 had nowhere to put the scale the
 * frontend was already sending, so a 1~5 question and a 1~10 question stored identically.
 */
@Entity
@Table(name = "survey_questions")
public class SurveyQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false, updatable = false)
    private Long surveyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 30)
    private SurveyQuestionType questionType;

    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    private String questionText;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    /** 0-based and contiguous; the response array order is this order. */
    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    /** Both set for {@code RATING}, both {@code null} otherwise — {@code ck_survey_questions_rating}. */
    @Column(name = "rating_min")
    private Short ratingMin;

    @Column(name = "rating_max")
    private Short ratingMax;

    protected SurveyQuestion() {
    }

    public SurveyQuestion(Long surveyId, SurveyQuestionType questionType, String questionText,
                          boolean required, short displayOrder, Short ratingMin, Short ratingMax) {
        this.surveyId = surveyId;
        this.questionType = questionType;
        this.questionText = questionText;
        this.required = required;
        this.displayOrder = displayOrder;
        this.ratingMin = ratingMin;
        this.ratingMax = ratingMax;
    }

    /** {@code true} when {@code value} sits inside this scale. Only meaningful for a RATING. */
    public boolean acceptsRating(int value) {
        return ratingMin != null && ratingMax != null && value >= ratingMin && value <= ratingMax;
    }

    public Long getId() {
        return id;
    }

    public Long getSurveyId() {
        return surveyId;
    }

    public SurveyQuestionType getQuestionType() {
        return questionType;
    }

    public String getQuestionText() {
        return questionText;
    }

    public boolean isRequired() {
        return required;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }

    public Short getRatingMin() {
        return ratingMin;
    }

    public Short getRatingMax() {
        return ratingMax;
    }
}
