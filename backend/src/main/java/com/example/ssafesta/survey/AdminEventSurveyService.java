package com.example.ssafesta.survey;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminGuard;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Supplies the identifying event entrant view that ordinary survey result paths must never expose. */
@Service
public class AdminEventSurveyService {

    /** How many free-text answers {@link #aggregate} samples per text question — a peek, not a page. */
    private static final int TEXT_SAMPLE_SIZE = 5;

    private final AdminGuard admins;
    private final SurveyRepository surveys;
    private final SurveyResponseRepository responses;
    private final SurveyQuestionRepository questions;
    private final SurveyOptionRepository options;
    private final SurveyAggregateRepository aggregates;
    private final SurveyAnswerRepository answers;
    private final UserRepository users;

    public AdminEventSurveyService(AdminGuard admins, SurveyRepository surveys,
                                   SurveyResponseRepository responses, SurveyQuestionRepository questions,
                                   SurveyOptionRepository options, SurveyAggregateRepository aggregates,
                                   SurveyAnswerRepository answers, UserRepository users) {
        this.admins = admins;
        this.surveys = surveys;
        this.responses = responses;
        this.questions = questions;
        this.options = options;
        this.aggregates = aggregates;
        this.answers = answers;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<EventEntrantView> entrants(Long actorUserId, String surveyKey, Pageable pageable) {
        Survey event = requireEvent(actorUserId, surveyKey);
        return responses.findEventEntrants(event.getId(), pageable)
                .map(row -> new EventEntrantView(row.getResponseId(), row.getUserId(), row.getNickname(),
                        row.getSubmittedAt()));
    }

    /** The console's landing list (S15P21A604-832) — one row per event survey, no known-key constant needed. */
    @Transactional(readOnly = true)
    public List<EventSurveyView> list(Long actorUserId) {
        admins.requireAdmin(actorUserId);
        Instant now = Instant.now();
        return surveys.findBySurveyKeyIsNotNullOrderByIdAsc().stream()
                .map(survey -> new EventSurveyView(survey.getSurveyKey(), survey.getId(), survey.getTitle(),
                        survey.isClosedAt(now), survey.getRewardCoin(),
                        (int) questions.countBySurveyId(survey.getId()),
                        responses.countBySurveyId(survey.getId())))
                .toList();
    }

    /**
     * Per-question numbers, the same shape {@link SurveyResultService#results} builds for a booth
     * survey — but reached through {@link AdminGuard} rather than the booth editor guard, since an
     * event survey has no booth to own it.
     */
    @Transactional(readOnly = true)
    public List<QuestionAggregateView> aggregate(Long actorUserId, String surveyKey) {
        Survey event = requireEvent(actorUserId, surveyKey);
        Long surveyId = event.getId();

        List<SurveyQuestion> rows = questions.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        Map<Long, Long> answered = new HashMap<>();
        for (SurveyAggregateRepository.QuestionAnswered row : aggregates.answeredCounts(surveyId)) {
            answered.put(row.getQuestionId(), row.getAnswered());
        }
        Map<Long, Map<Long, Long>> picks = new HashMap<>();
        for (SurveyAggregateRepository.OptionPicked row : aggregates.optionCounts(surveyId)) {
            picks.computeIfAbsent(row.getQuestionId(), key -> new HashMap<>())
                    .put(row.getOptionId(), row.getPicked());
        }
        Map<Long, List<SurveyOption>> optionsByQuestion = new HashMap<>();
        if (!rows.isEmpty()) {
            options.findByQuestionIdInOrderByQuestionIdAscDisplayOrderAsc(
                            rows.stream().map(SurveyQuestion::getId).toList())
                    .forEach(option -> optionsByQuestion
                            .computeIfAbsent(option.getQuestionId(), key -> new ArrayList<>()).add(option));
        }

        List<QuestionAggregateView> result = new ArrayList<>(rows.size());
        for (SurveyQuestion question : rows) {
            List<OptionCountView> counts = List.of();
            if (question.getQuestionType().isChoice()) {
                counts = optionsByQuestion.getOrDefault(question.getId(), List.of()).stream()
                        .map(option -> new OptionCountView(option.getOptionText(),
                                picks.getOrDefault(question.getId(), Map.of())
                                        .getOrDefault(option.getId(), 0L)))
                        .toList();
            }
            List<String> textSamples = List.of();
            if (question.getQuestionType().isText()) {
                textSamples = answers.findTextAnswers(surveyId, question.getId(), TEXT_SAMPLE_SIZE, 0).stream()
                        .map(SurveyAnswerRepository.TextAnswerRow::getText)
                        .toList();
            }
            result.add(new QuestionAggregateView(question.getId(), question.getQuestionType().name(),
                    question.getQuestionText(), answered.getOrDefault(question.getId(), 0L), counts,
                    textSamples));
        }
        return result;
    }

    /** One respondent's answers, identity included — the same boundary {@link #entrants} draws. */
    @Transactional(readOnly = true)
    public EventResponseView responseDetail(Long actorUserId, String surveyKey, Long responseId) {
        Survey event = requireEvent(actorUserId, surveyKey);
        SurveyResponse response = responses.findByIdAndSurveyId(responseId, event.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Long respondentUserId = response.getRespondentUserId();
        User respondent = users.findById(respondentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        Map<Long, SurveyQuestion> questionById = new HashMap<>();
        questions.findBySurveyIdOrderByDisplayOrderAsc(event.getId())
                .forEach(question -> questionById.put(question.getId(), question));

        List<SurveyAnswer> rows = answers.findByResponseIdOrderByQuestionIdAsc(responseId);
        Map<Long, List<String>> selectedLabels = new HashMap<>();
        List<Long> answerIds = rows.stream().map(SurveyAnswer::getId).toList();
        if (!answerIds.isEmpty()) {
            for (SurveyAnswerRepository.SelectedOptionRow row : answers.findSelectedOptionLabels(answerIds)) {
                selectedLabels.computeIfAbsent(row.getAnswerId(), key -> new ArrayList<>())
                        .add(row.getOptionText());
            }
        }

        List<AnswerView> answerViews = rows.stream()
                .map(answer -> {
                    SurveyQuestion question = questionById.get(answer.getQuestionId());
                    return new AnswerView(answer.getQuestionId(), question.getQuestionText(),
                            valueOf(answer, question, selectedLabels.getOrDefault(answer.getId(), List.of())));
                })
                .toList();
        return new EventResponseView(responseId, respondentUserId, respondent.getNickname(),
                response.getSubmittedAt(), answerViews);
    }

    private static String valueOf(SurveyAnswer answer, SurveyQuestion question, List<String> selectedLabels) {
        if (question.getQuestionType().isChoice()) {
            return String.join(", ", selectedLabels);
        }
        if (question.getQuestionType().isRating()) {
            return String.valueOf(answer.getRatingValue());
        }
        return answer.getTextAnswer();
    }

    private Survey requireEvent(Long actorUserId, String surveyKey) {
        admins.requireAdmin(actorUserId);
        return surveys.findBySurveyKey(surveyKey)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
    }

    /** Identity is intentionally available only from the admin event-operation endpoint. */
    public record EventEntrantView(Long responseId, Long userId, String nickname, Instant submittedAt) { }

    public record EventSurveyView(String surveyKey, Long surveyId, String title, boolean closed,
                                  int rewardCoin, int questionCount, long entrantCount) { }

    @Schema(name = "AdminEventSurveyQuestionAggregate")
    public record QuestionAggregateView(Long questionId, String type, String prompt, long answered,
                                        List<OptionCountView> options, List<String> textSamples) { }

    @Schema(name = "AdminEventSurveyOptionCount")
    public record OptionCountView(String label, long count) { }

    public record EventResponseView(Long responseId, Long userId, String nickname, Instant submittedAt,
                                    List<AnswerView> answers) { }

    public record AnswerView(Long questionId, String prompt, String value) { }
}
