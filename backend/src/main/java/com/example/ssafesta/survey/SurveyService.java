package com.example.ssafesta.survey;

import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.booth.BoothNotFoundException;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ConstraintViolations;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.PresenceField;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing a booth survey and serving it to a visitor (spec 010 US1, S15P21A604-130 · -190).
 *
 * <p>Three entry points, and the difference between them is who is asking:
 * {@link #findForEditor} and {@link #upsert} are the Builder, {@link #findRun} is the overlay a
 * visitor opens from the kiosk.
 *
 * <p><b>One survey per booth</b> (C-06), so there is no create/update split on the wire — the
 * frontend Port has {@code getDraft()}/{@code saveDraft()} with no id at all, and a PUT that means
 * "make it look like this" matches that exactly.
 *
 * <p><b>Saving publishes</b> (C-07). The Builder has no publish button, so a saved survey is a live
 * one; its own validation (title, prompts, two options minimum) runs before anything reaches here,
 * which is why a half-built survey does not become visible.
 */
@Service
public class SurveyService {

    /** Column widths and screen limits, not policy — the three policy caps live in properties. */
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;
    private static final int MAX_PROMPT_LENGTH = 500;
    private static final int MAX_OPTION_LABEL_LENGTH = 500;
    private static final int MIN_CHOICE_OPTIONS = 2;
    private static final short RATING_FLOOR = 1;
    private static final short RATING_CEILING = 10;

    private final SurveyRepository surveys;
    private final SurveyQuestionRepository questions;
    private final SurveyOptionRepository options;
    private final SurveyResponseRepository responses;
    private final BoothAccessGuard accessGuard;
    private final BoothRepository booths;
    private final SurveyProperties properties;

    public SurveyService(SurveyRepository surveys, SurveyQuestionRepository questions,
                         SurveyOptionRepository options, SurveyResponseRepository responses,
                         BoothAccessGuard accessGuard, BoothRepository booths,
                         SurveyProperties properties) {
        this.surveys = surveys;
        this.questions = questions;
        this.options = options;
        this.responses = responses;
        this.accessGuard = accessGuard;
        this.booths = booths;
        this.properties = properties;
    }

    // ── 편집자 조회 (계약 §3) ────────────────────────────────────────────────

    /**
     * What the Builder loads.
     *
     * <p>Editor only, and deliberately <b>without</b> the lease check: an owner whose lease ended
     * must still be able to read their own survey, which is what FR-011's 보존 means in practice.
     * {@code BoothLayoutQueryService.findDraft} makes the same exception for the same reason.
     *
     * @throws ApiException {@code SURVEY_NOT_FOUND} when none exists yet — not an error state for
     *                      the client, which reads it as "no survey made yet"
     */
    @Transactional(readOnly = true)
    public SurveyView findForEditor(Long boothId, Long userId) {
        accessGuard.requireEditor(boothId, userId);
        Survey survey = surveys.findByBoothId(boothId)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
        return view(survey, Instant.now());
    }

    // ── 저장 (계약 §4) ──────────────────────────────────────────────────────

    /**
     * Creates or replaces the booth survey. Always {@code 200} — the client says "save", not
     * "create" or "update".
     *
     * <p>Order is the contract (data-model §4): permission, then lease, then the row lock, then the
     * lock rule, then fields. Someone with no claim on this booth is told that, not that the survey
     * they cannot see is locked.
     *
     * <p>The booth row is locked for write because a concurrent submission inserts answers pointing
     * at the questions this method is about to delete, and {@code survey_answers.question_id} has no
     * {@code ON DELETE}. Submission takes the shared lock on the same row.
     *
     * @throws ApiException {@code SURVEY_LOCKED} when the survey has responses and the incoming
     *                      question set differs structurally (C-08)
     */
    @Transactional
    public SurveyView upsert(Long boothId, Long userId, SurveyCommand command) {
        Booth booth = accessGuard.requireActiveEditor(boothId, userId);
        booths.findWithLockById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));

        SurveyCommand body = command == null ? new SurveyCommand() : command;
        Instant now = Instant.now();

        Survey existing = surveys.findByBoothId(boothId).orElse(null);
        List<SurveyQuestion> currentQuestions = existing == null
                ? List.of()
                : questions.findBySurveyIdOrderByDisplayOrderAsc(existing.getId());
        Map<Long, List<SurveyOption>> currentOptions = optionsByQuestion(currentQuestions);

        boolean locked = existing != null && responses.countBySurveyId(existing.getId()) > 0;
        if (locked && !sameStructure(currentQuestions, currentOptions, body.questions())) {
            throw new ApiException(ErrorCode.SURVEY_LOCKED);
        }

        validate(body, existing);

        String title = body.title().trim();
        String description = body.description().isPresent()
                ? blankToNull(body.description().value())
                : (existing == null ? null : existing.getDescription());
        int rewardCoin = body.rewardCoin().isPresent()
                ? nullToZero(body.rewardCoin().value())
                : (existing == null ? 0 : existing.getRewardCoin());
        Instant closesAt = body.closesAt().isPresent()
                ? body.closesAt().value()
                : (existing == null ? null : existing.getEndsAt());

        Survey survey;
        if (existing == null) {
            survey = create(new Survey(boothId, booth.getOwnerUserId(), title, description,
                    rewardCoin, closesAt, now));
        } else {
            existing.update(title, description, rewardCoin, closesAt, now);
            survey = existing;
        }

        if (!locked) {
            replaceQuestions(survey.getId(), currentQuestions, body.questions());
        }
        return view(survey, now);
    }

    /**
     * {@code ux_surveys_booth} is what actually enforces one survey per booth — two concurrent
     * saves both read "none" before either writes. The lock above closes that window for callers
     * that take it; the translation stays because a constraint the user can trip must answer with a
     * reason, not a 500.
     */
    private Survey create(Survey survey) {
        try {
            return surveys.saveAndFlush(survey);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.isViolationOf(exception, "ux_surveys_booth")) {
                // 이 부스에 설문이 방금 생겼다. PUT 의 뜻은 "이 모양이 되게 해라" 이므로
                // 경합에서 진 쪽에게 오류를 주지 않고 그 행을 갱신한다.
                Survey winner = surveys.findByBoothId(survey.getBoothId())
                        .orElseThrow(() -> exception);
                winner.update(survey.getTitle(), survey.getDescription(), survey.getRewardCoin(),
                        survey.getEndsAt(), Instant.now());
                return winner;
            }
            throw exception;
        }
    }

    /**
     * Options first, then questions, then the new set — {@code survey_options.question_id} has no
     * {@code ON DELETE}, and the bulk deletes flush before the inserts so the new rows do not race
     * the old ones for {@code UNIQUE(survey_id, display_order)}.
     */
    private void replaceQuestions(Long surveyId, List<SurveyQuestion> current,
                                  List<QuestionCommand> incoming) {
        if (!current.isEmpty()) {
            options.deleteAllByQuestionIdIn(current.stream().map(SurveyQuestion::getId).toList());
            questions.deleteAllBySurveyId(surveyId);
        }
        short order = 0;
        for (QuestionCommand question : incoming) {
            SurveyQuestionType type = SurveyQuestionType.valueOf(question.type().trim().toUpperCase());
            Short min = type.isRating() ? (short) question.scale().min() : null;
            Short max = type.isRating() ? (short) question.scale().max() : null;
            SurveyQuestion saved = questions.save(new SurveyQuestion(surveyId, type,
                    question.prompt().trim(), Boolean.TRUE.equals(question.required()), order, min, max));
            if (type.isChoice()) {
                short optionOrder = 0;
                for (OptionCommand option : question.options()) {
                    options.save(new SurveyOption(saved.getId(), option.label().trim(), optionOrder));
                    optionOrder++;
                }
            }
            order++;
        }
    }

    // ── 방문자 조회 (계약 §5) ───────────────────────────────────────────────

    /**
     * What the kiosk overlay opens. Unity sends {@code {boothId, objectId}} and no survey id, so the
     * booth is the key and the response hands back the {@code surveyId} every later call needs.
     *
     * <p>A closed survey still returns its questions: the screen says 마감 while showing what was
     * asked. Submission is what refuses (계약 §6).
     */
    @Transactional(readOnly = true)
    public RunView findRun(Long boothId) {
        accessGuard.requireVisitorVisible(boothId);
        Survey survey = surveys.findByBoothId(boothId)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
        Instant now = Instant.now();
        return new RunView(survey.getId(), survey.isClosedAt(now), survey.getRewardCoin(),
                questionViews(survey.getId()));
    }

    /**
     * The festival's own survey, opened from the event prize shop (S15P21A604-621, GitLab #173).
     *
     * <p>Keyed by the event rather than by a booth, and <b>no booth gate runs</b> — there is no
     * lease or published layout to check because there is no booth. That absence is the point: a
     * booth-shaped event survey would need a fake user, lease and layout, and the day one of them
     * expired the event would quietly 404.
     *
     * <p><b>{@code responded} rides along.</b> Re-entry has to say "이미 참여함" before the member
     * presses anything, and a second endpoint for that one value would add a round trip to every
     * open of the screen.
     *
     * <p>A closed survey still returns its questions, exactly like {@link #findRun} — the screen
     * says 마감 while showing what was asked.
     *
     * @param memberId the caller; guests never reach here (the controller refuses them)
     */
    @Transactional(readOnly = true)
    public EventRunView findEventRun(String surveyKey, Long memberId) {
        Survey survey = surveys.findBySurveyKey(surveyKey)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
        RespondedView responded = responses
                .findBySurveyIdAndRespondentUserId(survey.getId(), memberId)
                .map(response -> new RespondedView(response.getId(), response.getSubmittedAt()))
                .orElse(null);
        return new EventRunView(survey.getSurveyKey(), survey.getId(),
                survey.isClosedAt(Instant.now()), survey.getRewardCoin(), true, responded,
                questionViews(survey.getId()));
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    private void validate(SurveyCommand body, Survey existing) {
        if (body.title() == null || body.title().isBlank()) {
            throw ApiException.fieldInvalid("title", "설문 제목을 입력해주세요.");
        }
        if (body.title().trim().length() > MAX_TITLE_LENGTH) {
            throw ApiException.fieldInvalid("title", "설문 제목은 " + MAX_TITLE_LENGTH + "자를 넘을 수 없습니다.");
        }
        if (body.description().isPresent() && body.description().value() != null
                && body.description().value().length() > MAX_DESCRIPTION_LENGTH) {
            throw ApiException.fieldInvalid("description",
                    "설명은 " + MAX_DESCRIPTION_LENGTH + "자를 넘을 수 없습니다.");
        }
        if (body.rewardCoin().isPresent()) {
            int reward = nullToZero(body.rewardCoin().value());
            if (reward < 0) {
                throw ApiException.fieldInvalid("rewardCoin", "보상 코인은 0 이상이어야 합니다.");
            }
            if (reward > properties.maxRewardCoin()) {
                throw ApiException.fieldInvalid("rewardCoin",
                        "보상 코인은 " + properties.maxRewardCoin() + "을 넘을 수 없습니다.");
            }
        }
        // V1 의 CHECK(ends_at > starts_at) 는 starts_at 이 NULL 이라 과거 마감일을 못 잡는다.
        if (body.closesAt().isPresent() && body.closesAt().value() != null
                && !body.closesAt().value().isAfter(Instant.now())) {
            throw ApiException.fieldInvalid("closesAt", "마감 시각은 현재보다 뒤여야 합니다.");
        }

        List<QuestionCommand> list = body.questions();
        if (list.isEmpty()) {
            throw ApiException.fieldInvalid("questions", "문항을 1개 이상 추가해주세요.");
        }
        if (list.size() > properties.maxQuestions()) {
            throw ApiException.fieldInvalid("questions",
                    "문항은 " + properties.maxQuestions() + "개를 넘을 수 없습니다.");
        }
        for (int i = 0; i < list.size(); i++) {
            validateQuestion(list.get(i), "questions[" + i + "]");
        }
    }

    private void validateQuestion(QuestionCommand question, String path) {
        SurveyQuestionType type = parseType(question.type(), path + ".type");
        if (question.prompt() == null || question.prompt().isBlank()) {
            throw ApiException.fieldInvalid(path + ".prompt", "질문 내용을 입력해주세요.");
        }
        if (question.prompt().trim().length() > MAX_PROMPT_LENGTH) {
            throw ApiException.fieldInvalid(path + ".prompt",
                    "질문은 " + MAX_PROMPT_LENGTH + "자를 넘을 수 없습니다.");
        }

        List<OptionCommand> optionList = question.options();
        if (type.isChoice()) {
            if (optionList.size() < MIN_CHOICE_OPTIONS) {
                throw ApiException.fieldInvalid(path + ".options",
                        "선택지는 " + MIN_CHOICE_OPTIONS + "개 이상 필요합니다.");
            }
            if (optionList.size() > properties.maxOptions()) {
                throw ApiException.fieldInvalid(path + ".options",
                        "선택지는 " + properties.maxOptions() + "개를 넘을 수 없습니다.");
            }
            for (OptionCommand option : optionList) {
                if (option == null || option.label() == null || option.label().isBlank()) {
                    throw ApiException.fieldInvalid(path + ".options", "선택지 내용을 입력해주세요.");
                }
                if (option.label().trim().length() > MAX_OPTION_LABEL_LENGTH) {
                    throw ApiException.fieldInvalid(path + ".options",
                            "선택지는 " + MAX_OPTION_LABEL_LENGTH + "자를 넘을 수 없습니다.");
                }
            }
        } else if (!optionList.isEmpty()) {
            // 조용히 버리면 FE 의 유형 변경 버그가 저장 성공으로 보이고, 사라진 선택지를
            // 나중에 화면에서 찾게 된다.
            throw ApiException.fieldInvalid(path + ".options", "이 유형은 선택지를 가질 수 없습니다.");
        }

        if (type.isRating()) {
            ScaleCommand scale = question.scale();
            if (scale == null) {
                throw ApiException.fieldInvalid(path + ".scale", "별점 범위를 지정해주세요.");
            }
            if (scale.min() < RATING_FLOOR || scale.max() > RATING_CEILING) {
                throw ApiException.fieldInvalid(path + ".scale",
                        "별점 범위는 " + RATING_FLOOR + "~" + RATING_CEILING + " 안이어야 합니다.");
            }
            if (scale.min() >= scale.max()) {
                throw ApiException.fieldInvalid(path + ".scale", "별점 최솟값은 최댓값보다 작아야 합니다.");
            }
        } else if (question.scale() != null) {
            throw ApiException.fieldInvalid(path + ".scale", "이 유형은 별점 범위를 가질 수 없습니다.");
        }
    }

    private SurveyQuestionType parseType(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.fieldInvalid(field, "문항 유형을 지정해주세요.");
        }
        try {
            return SurveyQuestionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.fieldInvalid(field, "지원하지 않는 문항 유형입니다: " + raw);
        }
    }

    // ── 잠금 판정 (C-08) ────────────────────────────────────────────────────

    /**
     * Whether the incoming set is the same questions, asked the same way, in the same order.
     *
     * <p>Only the structure is compared, because only the structure is what the database refuses to
     * change once answers point at it. Title, description, reward and deadline stay editable — a
     * survey nobody can close is worse than one whose title has a typo.
     */
    private boolean sameStructure(List<SurveyQuestion> current, Map<Long, List<SurveyOption>> currentOptions,
                                  List<QuestionCommand> incoming) {
        if (current.size() != incoming.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            SurveyQuestion mine = current.get(i);
            QuestionCommand theirs = incoming.get(i);
            SurveyQuestionType type;
            try {
                type = SurveyQuestionType.valueOf(String.valueOf(theirs.type()).trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                return false;
            }
            if (mine.getQuestionType() != type
                    || !Objects.equals(mine.getQuestionText(), trimOrNull(theirs.prompt()))
                    || mine.isRequired() != Boolean.TRUE.equals(theirs.required())
                    || !sameScale(mine, theirs.scale())
                    || !sameOptions(currentOptions.getOrDefault(mine.getId(), List.of()), theirs.options())) {
                return false;
            }
        }
        return true;
    }

    private boolean sameScale(SurveyQuestion question, ScaleCommand scale) {
        if (!question.getQuestionType().isRating()) {
            return scale == null;
        }
        return scale != null
                && question.getRatingMin() != null && question.getRatingMin() == scale.min()
                && question.getRatingMax() != null && question.getRatingMax() == scale.max();
    }

    private boolean sameOptions(List<SurveyOption> current, List<OptionCommand> incoming) {
        if (current.size() != incoming.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!Objects.equals(current.get(i).getOptionText(), trimOrNull(incoming.get(i).label()))) {
                return false;
            }
        }
        return true;
    }

    // ── 조립 ────────────────────────────────────────────────────────────────

    private SurveyView view(Survey survey, Instant now) {
        return new SurveyView(survey.getId(), survey.getBoothId(), survey.getTitle(),
                survey.getDescription(), survey.getRewardCoin(), survey.getEndsAt(),
                survey.isClosedAt(now), responses.countBySurveyId(survey.getId()),
                questionViews(survey.getId()));
    }

    private List<QuestionView> questionViews(Long surveyId) {
        List<SurveyQuestion> rows = questions.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        Map<Long, List<SurveyOption>> byQuestion = optionsByQuestion(rows);
        List<QuestionView> views = new ArrayList<>(rows.size());
        for (SurveyQuestion question : rows) {
            List<OptionView> optionViews = byQuestion.getOrDefault(question.getId(), List.of()).stream()
                    .map(option -> new OptionView(option.getId(), option.getOptionText()))
                    .toList();
            ScaleView scale = question.getQuestionType().isRating()
                    ? new ScaleView(question.getRatingMin(), question.getRatingMax())
                    : null;
            views.add(new QuestionView(question.getId(), question.getQuestionType().name(),
                    question.getQuestionText(), question.isRequired(), question.getDisplayOrder(),
                    optionViews, scale));
        }
        return views;
    }

    private Map<Long, List<SurveyOption>> optionsByQuestion(List<SurveyQuestion> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SurveyOption>> byQuestion = new LinkedHashMap<>();
        options.findByQuestionIdInOrderByQuestionIdAscDisplayOrderAsc(
                        rows.stream().map(SurveyQuestion::getId).toList()).stream()
                .sorted(Comparator.comparing(SurveyOption::getDisplayOrder))
                .forEach(option -> byQuestion.computeIfAbsent(option.getQuestionId(),
                        key -> new ArrayList<>()).add(option));
        return byQuestion;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    // ── 요청 ────────────────────────────────────────────────────────────────

    /**
     * The save body. <b>Not a record</b>, and that is the point.
     *
     * <p>The frontend {@code saveDraft} sends {@code {title, questions}} only. With plain fields the
     * three optional ones would arrive as {@code null} and every autosave would wipe the reward an
     * operator set — 016 shipped exactly that collapse once (T-97). {@link PresenceField} makes
     * "absent" and "explicitly null" different requests: absent keeps, null clears.
     */
    public static class SurveyCommand {

        private String title;
        private final PresenceField<String> description = new PresenceField<>();
        private final PresenceField<Integer> rewardCoin = new PresenceField<>();
        private final PresenceField<Instant> closesAt = new PresenceField<>();
        private List<QuestionCommand> questions = List.of();

        @JsonProperty("title")
        public void setTitle(String title) {
            this.title = title;
        }

        @JsonProperty("description")
        public void setDescription(String description) {
            this.description.set(description);
        }

        @JsonProperty("rewardCoin")
        public void setRewardCoin(Integer rewardCoin) {
            this.rewardCoin.set(rewardCoin);
        }

        @JsonProperty("closesAt")
        public void setClosesAt(Instant closesAt) {
            this.closesAt.set(closesAt);
        }

        @JsonProperty("questions")
        public void setQuestions(List<QuestionCommand> questions) {
            this.questions = questions == null ? List.of() : List.copyOf(questions);
        }

        public String title() {
            return title;
        }

        public PresenceField<String> description() {
            return description;
        }

        public PresenceField<Integer> rewardCoin() {
            return rewardCoin;
        }

        public PresenceField<Instant> closesAt() {
            return closesAt;
        }

        public List<QuestionCommand> questions() {
            return questions;
        }
    }

    /** One question to save. {@code options}/{@code scale} belong to their type and only to it. */
    public record QuestionCommand(String type, String prompt, Boolean required,
                                  List<OptionCommand> options, ScaleCommand scale) {

        public QuestionCommand {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record OptionCommand(String label) {
    }

    public record ScaleCommand(int min, int max) {
    }

    // ── 응답 ────────────────────────────────────────────────────────────────

    /** Every key is always present; {@code null} means no value (계약 §1). */
    public record SurveyView(Long surveyId, Long boothId, String title, String description,
                             int rewardCoin, Instant closesAt, boolean closed, long responseCount,
                             List<QuestionView> questions) {
    }

    public record QuestionView(Long questionId, String type, String prompt, boolean required,
                               int order, List<OptionView> options, ScaleView scale) {
    }

    public record OptionView(Long optionId, String label) {
    }

    public record ScaleView(Short min, Short max) {
    }

    /**
     * What a visitor gets. {@code rewardCoin} is here so the overlay can tell a guest that a
     * rewarded survey is members-only <b>before</b> they fill it in and get a 403.
     */
    public record RunView(Long surveyId, boolean closed, int rewardCoin, List<QuestionView> questions) {
    }

    /**
     * The event survey run screen (S15P21A604-621, 계약 §11).
     *
     * <p>{@code questions} is the same shape as {@link RunView}'s so the client maps one form for
     * both sources. The two fields that differ are the two facts a booth survey has no way to
     * state:
     *
     * <ul>
     *   <li>{@code memberOnly} — always {@code true} here, and <b>independent of
     *       {@code rewardCoin}</b>. This survey pays nothing yet admits no guests, because the
     *       draw has to identify who entered. A client judging on {@code rewardCoin > 0} alone
     *       would open it to guests.
     *   <li>{@code responded} — {@code null} until this member takes part. Re-entry shows the
     *       completed state without a second call.
     * </ul>
     *
     * <p>{@code surveyKey} is echoed back so a late response can be matched to the request that
     * asked for it.
     */
    public record EventRunView(String surveyKey, Long surveyId, boolean closed, int rewardCoin,
                               boolean memberOnly, RespondedView responded,
                               List<QuestionView> questions) {
    }

    /** When this member already answered. */
    public record RespondedView(Long responseId, Instant submittedAt) {
    }
}
