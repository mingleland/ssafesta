package com.example.ssafesta.survey;

import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the operator sees after people answer (spec 010 US2, S15P21A604-132 · -193).
 *
 * <p><b>The server counts and the client draws.</b> Raw responses never leave (FR-007), and no ratio
 * is computed here either — the frontend divides {@code count} by {@code answeredCount} itself, which
 * is what lets a multiple-choice question sum past 100% without anyone having to decide whether that
 * is a bug.
 *
 * <p><b>Assembled from the question list, not from the answers.</b> Every question is reported even
 * when nobody answered it, every option even when nobody picked it, and every rating value on the
 * scale even when nobody chose it. Driving the loop off the aggregate rows instead would make an
 * unanswered question and a deleted one look identical, and would put a division by zero in the
 * average (FR-012 · SC-002).
 *
 * <p>No lease check, like {@link SurveyService#findForEditor} — results outlive the booth's lease
 * (FR-011). Reading them is also the one survey call that touches no booth state, so there is
 * nothing here to lock.
 */
@Service
public class SurveyResultService {

    /** The first page {@code GET .../results} embeds; the same default as the paged endpoint. */
    private static final int DEFAULT_TEXT_PAGE_SIZE = 20;
    private static final int MAX_TEXT_PAGE_SIZE = 100;

    private final SurveyRepository surveys;
    private final SurveyQuestionRepository questions;
    private final SurveyOptionRepository options;
    private final SurveyAggregateRepository aggregates;
    private final SurveyAnswerRepository answers;
    private final BoothAccessGuard accessGuard;

    public SurveyResultService(SurveyRepository surveys, SurveyQuestionRepository questions,
                               SurveyOptionRepository options, SurveyAggregateRepository aggregates,
                               SurveyAnswerRepository answers, BoothAccessGuard accessGuard) {
        this.surveys = surveys;
        this.questions = questions;
        this.options = options;
        this.aggregates = aggregates;
        this.answers = answers;
        this.accessGuard = accessGuard;
    }

    // ── 결과 (계약 §7) ──────────────────────────────────────────────────────

    /**
     * Every number the results screen needs, in one call.
     *
     * <p>Four {@code GROUP BY} reads plus the first text page, computed live rather than kept in a
     * counter column (C-04) — a stored total is a second thing that can disagree with the answers,
     * and SC-001 asks for the opposite of that.
     *
     * <p><b>{@code REPEATABLE_READ} because the five reads have to agree with each other.</b> Under
     * the default {@code READ_COMMITTED} each statement takes its own snapshot, so a submission
     * landing between them can put {@code answeredCount} above {@code totalResponses} — numbers
     * that never described any real state of the survey. One snapshot for the whole method costs
     * nothing here: a read-only transaction cannot lose a serialization conflict in PostgreSQL's
     * repeatable read.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResultsView results(Long surveyId, Long userId) {
        Survey survey = requireEditableSurvey(surveyId, userId);

        List<SurveyQuestion> rows = questions.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        SurveyAggregateRepository.ResponseTotals totals = aggregates.totals(surveyId);

        Map<Long, Long> answered = new HashMap<>();
        for (SurveyAggregateRepository.QuestionAnswered row : aggregates.answeredCounts(surveyId)) {
            answered.put(row.getQuestionId(), row.getAnswered());
        }
        Map<Long, Map<Long, Long>> picks = new HashMap<>();
        for (SurveyAggregateRepository.OptionPicked row : aggregates.optionCounts(surveyId)) {
            picks.computeIfAbsent(row.getQuestionId(), key -> new HashMap<>())
                    .put(row.getOptionId(), row.getPicked());
        }
        Map<Long, Map<Short, Long>> ratings = new HashMap<>();
        for (SurveyAggregateRepository.RatingBucket row : aggregates.ratingBuckets(surveyId)) {
            ratings.computeIfAbsent(row.getQuestionId(), key -> new HashMap<>())
                    .put(row.getRatingValue(), row.getPicked());
        }

        Map<Long, List<SurveyOption>> byQuestion = optionsByQuestion(rows);
        List<QuestionAggregateView> perQuestion = new ArrayList<>(rows.size());
        for (SurveyQuestion question : rows) {
            perQuestion.add(aggregate(question, answered.getOrDefault(question.getId(), 0L),
                    byQuestion.getOrDefault(question.getId(), List.of()),
                    picks.getOrDefault(question.getId(), Map.of()),
                    ratings.getOrDefault(question.getId(), Map.of())));
        }

        return new ResultsView(surveyId, totals.getTotal(), totals.getFirstAt(), totals.getLastAt(),
                perQuestion, page(surveyId, null, 0, DEFAULT_TEXT_PAGE_SIZE));
    }

    /**
     * One question's numbers. {@code counts}, {@code average} and {@code distribution} are present
     * whatever the type is — empty or {@code null} where they do not apply (계약 §1), so the client
     * reads the same shape for all six types.
     */
    private QuestionAggregateView aggregate(SurveyQuestion question, long answeredCount,
                                            List<SurveyOption> questionOptions,
                                            Map<Long, Long> picks, Map<Short, Long> buckets) {
        List<OptionCountView> counts = List.of();
        if (question.getQuestionType().isChoice()) {
            counts = questionOptions.stream()
                    .map(option -> new OptionCountView(option.getId(), option.getOptionText(),
                            picks.getOrDefault(option.getId(), 0L)))
                    .toList();
        }

        List<RatingBucketView> distribution = List.of();
        Double average = null;
        if (question.getQuestionType().isRating()) {
            distribution = distribution(question, buckets);
            average = average(distribution);
        }
        return new QuestionAggregateView(question.getId(), question.getQuestionType().name(),
                answeredCount, counts, average, distribution);
    }

    /**
     * The whole scale, values nobody chose included.
     *
     * <p>A histogram missing its empty bars is a different chart — "nobody gave a 1" has to be
     * visible, and only the question's own {@code ratingMin}/{@code ratingMax} know how far the
     * scale reaches.
     */
    private List<RatingBucketView> distribution(SurveyQuestion question, Map<Short, Long> buckets) {
        short min = question.getRatingMin();
        short max = question.getRatingMax();
        List<RatingBucketView> distribution = new ArrayList<>(max - min + 1);
        for (short value = min; value <= max; value++) {
            distribution.add(new RatingBucketView(value, buckets.getOrDefault(value, 0L)));
        }
        return distribution;
    }

    /**
     * The mean, <b>derived from the distribution that ships beside it</b> rather than from a second
     * {@code avg()} query — computed twice they can disagree, and then no one can say which is wrong.
     *
     * <p>One decimal place (data-model §5). {@code null} when nobody answered: there is no mean of
     * nothing, and {@code 0.0} would draw as a real rating (FR-012).
     */
    private Double average(List<RatingBucketView> distribution) {
        long total = 0;
        long weighted = 0;
        for (RatingBucketView bucket : distribution) {
            total += bucket.count();
            weighted += (long) bucket.value() * bucket.count();
        }
        if (total == 0) {
            return null;
        }
        return Math.round(weighted * 10.0 / total) / 10.0;
    }

    // ── 주관식 페이지 (계약 §8) ─────────────────────────────────────────────

    /**
     * The pages after the one {@link #results} embeds (FR-008).
     *
     * <p>Same single snapshot as {@link #results}: {@code totalElements} and the rows are counted
     * and read by two statements, and a page whose total disagrees with its own content is worse
     * than a page one submission out of date.
     *
     * @param questionId one text question, or {@code null} for all three text types
     * @throws ApiException {@code VALIDATION_FAILED} for a negative page, a size outside 1~100, or a
     *                      {@code questionId} belonging to another survey
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TextAnswerPage textAnswers(Long surveyId, Long userId, Long questionId, int page, int size) {
        requireEditableSurvey(surveyId, userId);
        if (page < 0) {
            throw ApiException.fieldInvalid("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_TEXT_PAGE_SIZE) {
            throw ApiException.fieldInvalid("size",
                    "size는 1 이상 " + MAX_TEXT_PAGE_SIZE + " 이하여야 합니다.");
        }
        if (questionId != null && !questions.existsByIdAndSurveyId(questionId, surveyId)) {
            throw ApiException.fieldInvalid("questionId", "이 설문의 문항이 아닙니다.");
        }
        return page(surveyId, questionId, page, size);
    }

    private TextAnswerPage page(Long surveyId, Long questionId, int page, int size) {
        long total = answers.countTextAnswers(surveyId, questionId);
        List<TextAnswerView> content = answers
                .findTextAnswers(surveyId, questionId, size, (long) page * size).stream()
                .map(row -> new TextAnswerView(row.getResponseId(), row.getQuestionId(), row.getText()))
                .toList();
        return new TextAnswerPage(content, page, size, total,
                (int) Math.ceil((double) total / size));
    }

    // ── 공통 ────────────────────────────────────────────────────────────────

    /**
     * The survey, if this member may read its results.
     *
     * <p>Missing before forbidden: a survey id nobody owns is a 404 whoever asks, and a 403 on an
     * id that does not exist would answer a question about someone else's booth.
     */
    private Survey requireEditableSurvey(Long surveyId, Long userId) {
        Survey survey = surveys.findById(surveyId)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
        accessGuard.requireEditor(survey.getBoothId(), userId);
        return survey;
    }

    private Map<Long, List<SurveyOption>> optionsByQuestion(List<SurveyQuestion> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SurveyOption>> byQuestion = new LinkedHashMap<>();
        options.findByQuestionIdInOrderByQuestionIdAscDisplayOrderAsc(
                        rows.stream().map(SurveyQuestion::getId).toList())
                .forEach(option -> byQuestion.computeIfAbsent(option.getQuestionId(),
                        key -> new ArrayList<>()).add(option));
        return byQuestion;
    }

    // ── 응답 ────────────────────────────────────────────────────────────────

    /**
     * The results payload. No respondent field anywhere in it (FR-009 · SC-003) — {@code responseId}
     * on a text answer groups one person's answers together and names nobody.
     */
    public record ResultsView(Long surveyId, long totalResponses, Instant firstRespondedAt,
                              Instant lastRespondedAt, List<QuestionAggregateView> perQuestion,
                              TextAnswerPage textAnswers) {
    }

    /** {@code counts}·{@code average}·{@code distribution} are always present (계약 §1). */
    public record QuestionAggregateView(Long questionId, String type, long answeredCount,
                                        List<OptionCountView> counts, Double average,
                                        List<RatingBucketView> distribution) {
    }

    /** No ratio — {@code count / answeredCount} is the client's to compute (계약 §7). */
    public record OptionCountView(Long optionId, String label, long count) {
    }

    public record RatingBucketView(short value, long count) {
    }

    /** The repository's page shape ({@code OpenApiConfiguration} §페이지 규약). */
    public record TextAnswerPage(List<TextAnswerView> content, int page, int size,
                                 long totalElements, int totalPages) {
    }

    public record TextAnswerView(Long responseId, Long questionId, String text) {
    }
}
