package com.example.ssafesta.survey;

/**
 * The six question types spec 010 FR-002 names (객관식·복수선택·별점·단답·장문·지원서).
 *
 * <p>Stored as the string, matching {@code ck_survey_questions_type} in V22 and the vocabulary
 * {@code docs/09} §16 already used. The frontend speaks lowercase ({@code single}, {@code multi}, …)
 * and its adapter maps the pair — the wire value is uppercase because every other enum in this API
 * is.
 *
 * <p>{@link #APPLICATION} stores and validates exactly like {@link #LONG_TEXT}. Spec C-03 says a
 * 지원서 eventually needs a per-submitter view, which is a read path rather than a different column;
 * until that is decided the text answers carry a {@code responseId} so the key exists when someone
 * builds it.
 */
public enum SurveyQuestionType {

    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    RATING,
    SHORT_TEXT,
    LONG_TEXT,
    APPLICATION;

    /** Needs {@code options}, and an answer picks from them. */
    public boolean isChoice() {
        return this == SINGLE_CHOICE || this == MULTIPLE_CHOICE;
    }

    /** Needs {@code scale}, and an answer is a number inside it. */
    public boolean isRating() {
        return this == RATING;
    }

    /** Answered with free text. The three differ only in how long that text may be. */
    public boolean isText() {
        return this == SHORT_TEXT || this == LONG_TEXT || this == APPLICATION;
    }

    /** 단답 200자, 장문·지원서 2,000자 — 화면이 감당하는 길이이고 컬럼은 {@code TEXT} 다. */
    public int maxTextLength() {
        return this == SHORT_TEXT ? 200 : 2_000;
    }
}
