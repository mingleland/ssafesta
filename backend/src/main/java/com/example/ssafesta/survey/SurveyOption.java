package com.example.ssafesta.survey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One choice on a {@code SINGLE_CHOICE} or {@code MULTIPLE_CHOICE} question (spec 010 FR-002).
 *
 * <p>Plain {@code questionId} for the reason given on {@link SurveyQuestion}. {@code displayOrder}
 * is {@code short} against the {@code SMALLINT} column.
 */
@Entity
@Table(name = "survey_options")
public class SurveyOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false, updatable = false)
    private Long questionId;

    @Column(name = "option_text", nullable = false, length = 500)
    private String optionText;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    protected SurveyOption() {
    }

    public SurveyOption(Long questionId, String optionText, short displayOrder) {
        this.questionId = questionId;
        this.optionText = optionText;
        this.displayOrder = displayOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getOptionText() {
        return optionText;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }
}
