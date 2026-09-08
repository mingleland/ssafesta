package com.example.ssafesta.survey;

import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.booth.BoothNotFoundException;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ConstraintViolations;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.wallet.CoinCreditCommand;
import com.example.ssafesta.wallet.CoinReason;
import com.example.ssafesta.wallet.LedgerEntryType;
import com.example.ssafesta.wallet.LedgerResult;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submitting one response, and paying for it (spec 010 FR-004·FR-005, S15P21A604-131 · -192).
 *
 * <p>Members and guests both answer (C-05). A guest is identified by the subject of their access
 * token, which is minted fresh per {@code /auth/guest} call — so "one response per guest" holds
 * within one browser session and not beyond it. There is no way to identify someone without an
 * account any further, and accepting that is the premise of C-05.
 *
 * <p><b>A rewarded survey refuses guests</b> before they fill it in. Guests have no wallet (헌법
 * 12조), so the payment would fail at the end; asking someone to answer and then telling them there
 * is nothing for them is worse than not letting them start.
 */
@Service
public class SurveyResponseService {

    private static final String REWARD_REFERENCE_TYPE = "SURVEY";
    private static final String GUEST_ONLY_MESSAGE =
            "보상이 있는 설문은 회원만 참여할 수 있습니다.";

    private final SurveyRepository surveys;
    private final SurveyQuestionRepository questions;
    private final SurveyOptionRepository options;
    private final SurveyResponseRepository responses;
    private final SurveyAnswerRepository answers;
    private final BoothAccessGuard accessGuard;
    private final BoothRepository booths;
    private final WalletService wallets;

    public SurveyResponseService(SurveyRepository surveys, SurveyQuestionRepository questions,
                                 SurveyOptionRepository options, SurveyResponseRepository responses,
                                 SurveyAnswerRepository answers, BoothAccessGuard accessGuard,
                                 BoothRepository booths, WalletService wallets) {
        this.surveys = surveys;
        this.questions = questions;
        this.options = options;
        this.responses = responses;
        this.answers = answers;
        this.accessGuard = accessGuard;
        this.booths = booths;
        this.wallets = wallets;
    }

    /**
     * One submission, and the reward it earns.
     *
     * <p>Order is the contract (data-model §4) and two steps of it are not interchangeable.
     *
     * <p><b>The reward comes after the response has flushed.</b> PostgreSQL marks a transaction as
     * aborted once a constraint fails, so nothing may run after the duplicate-submission catch —
     * paying first and inserting second would leave no way to answer the duplicate at all.
     * {@code GameAssetRepository} records the same fact for the same reason.
     *
     * <p><b>Both live in one transaction.</b> 헌법 20조 wants the coin movement and the thing that
     * earned it to commit together; {@code WalletService} propagates {@code REQUIRED} throughout, so
     * it joins this one rather than opening its own.
     *
     * <p><b>Booth first, wallet second — do not swap these.</b> Every path that touches both takes
     * them in this order: {@code BoothLeaseService.lease} updates the booth row and then charges,
     * and this method locks the booth and then credits. Nothing takes the wallet first, so there is
     * no cycle to deadlock on; moving the payout above the booth lock would create one.
     *
     * <p><b>The survey is read after the booth lock, not before.</b> An editor may change
     * {@code rewardCoin} and {@code closesAt} even once responses exist (C-08), and it holds the
     * booth write lock while doing so. A survey loaded before this lock is a snapshot from before
     * that edit — so the payout would be the old amount and a survey closed a moment ago would
     * still accept answers. Only the booth id is read first, because the lock needs it.
     */
    @Transactional
    public SubmitResult submit(Long surveyId, Respondent respondent, SubmitCommand command) {
        Long boothId = surveys.findBoothIdById(surveyId)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
        accessGuard.requireVisitorVisible(boothId);
        // 편집이 문항을 바꾸는 동안 답을 넣으면 survey_answers.question_id FK 에 걸린다.
        // 공유 락이라 응답자끼리는 줄 서지 않는다.
        booths.findWithSharedLockById(boothId).orElseThrow(() -> new BoothNotFoundException(boothId));

        // 설문은 락을 잡은 뒤에 읽는다. 그 전에 읽으면 지급액·마감이 락이 막고 있는 편집보다
        // 오래된 값이 된다.
        Survey survey = surveys.findById(surveyId)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));

        if (survey.isClosedAt(Instant.now())) {
            throw new ApiException(ErrorCode.SURVEY_CLOSED);
        }
        if (respondent.isGuest() && survey.hasReward()) {
            throw new ApiException(ErrorCode.MEMBER_ONLY, GUEST_ONLY_MESSAGE);
        }

        List<SurveyQuestion> questionRows = questions.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        Map<Long, Set<Long>> allowedOptions = allowedOptionIds(questionRows);
        List<Submission> submissions = validate(command, questionRows, allowedOptions);

        if (alreadyResponded(surveyId, respondent)) {
            throw new ApiException(ErrorCode.SURVEY_ALREADY_RESPONDED);
        }

        SurveyResponse response = flush(respondent.newResponse(surveyId, Instant.now()));
        for (Submission submission : submissions) {
            store(response.getId(), submission);
        }

        int rewarded = 0;
        if (respondent.isMember() && survey.hasReward()) {
            LedgerResult result = wallets.credit(new CoinCreditCommand(respondent.userId(),
                    LedgerEntryType.REWARD, survey.getRewardCoin(), CoinReason.SURVEY_REWARD,
                    REWARD_REFERENCE_TYPE, String.valueOf(surveyId),
                    rewardKey(surveyId, respondent.userId())));
            response.linkReward(result.entryId());
            rewarded = survey.getRewardCoin();
        }
        return new SubmitResult(response.getId(), rewarded);
    }

    /**
     * The pre-check that turns the common duplicate into a friendly 409 rather than a constraint
     * error. The indexes are what actually enforce it — see {@link #flush}.
     */
    private boolean alreadyResponded(Long surveyId, Respondent respondent) {
        return respondent.isMember()
                ? responses.existsBySurveyIdAndRespondentUserId(surveyId, respondent.userId())
                : responses.existsBySurveyIdAndRespondentGuestKey(surveyId, respondent.guestKey());
    }

    /**
     * Two requests can both pass the pre-check before either writes, so the partial unique indexes
     * decide it. The prefix match covers both — {@code ux_survey_responses_member} and
     * {@code _guest} — because {@code isViolationOf} compares by containment.
     */
    private SurveyResponse flush(SurveyResponse response) {
        try {
            return responses.saveAndFlush(response);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.isViolationOf(exception, "ux_survey_responses_")) {
                throw new ApiException(ErrorCode.SURVEY_ALREADY_RESPONDED);
            }
            throw exception;
        }
    }

    private void store(Long responseId, Submission submission) {
        SurveyQuestion question = submission.question();
        if (question.getQuestionType().isChoice()) {
            SurveyAnswer answer = answers.save(SurveyAnswer.ofChoice(responseId, question.getId()));
            answers.flush();
            for (Long optionId : submission.optionIds()) {
                answers.insertSelectedOption(answer.getId(), optionId);
            }
        } else if (question.getQuestionType().isRating()) {
            answers.save(SurveyAnswer.ofRating(responseId, question.getId(), submission.rating()));
        } else {
            answers.save(SurveyAnswer.ofText(responseId, question.getId(), submission.text()));
        }
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    /**
     * Turns the request into the answers worth storing, refusing anything the survey did not ask.
     *
     * <p>Nothing here trusts the client's view of the survey (헌법 16조): question ids must belong to
     * this survey, option ids to that question, and a rating to that question's own scale.
     *
     * <p>An unanswered optional question produces <b>no submission</b>, which is what makes
     * {@code answeredCount} differ from {@code totalResponses} without anyone tracking it. "Blank
     * means unanswered" matches the frontend's {@code isEmptyAnswer} exactly — if it did not, a
     * request the submit button allowed would come back as a 400.
     */
    private List<Submission> validate(SubmitCommand command, List<SurveyQuestion> questionRows,
                                      Map<Long, Set<Long>> allowedOptions) {
        Map<Long, SurveyQuestion> byId = new LinkedHashMap<>();
        questionRows.forEach(question -> byId.put(question.getId(), question));

        List<AnswerCommand> incoming = command == null ? List.of() : command.answers();
        List<Submission> submissions = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < incoming.size(); i++) {
            AnswerCommand answer = incoming.get(i);
            String path = "answers[" + i + "]";
            if (answer == null || answer.questionId() == null) {
                throw ApiException.fieldInvalid(path + ".questionId", "문항 식별자가 필요합니다.");
            }
            SurveyQuestion question = byId.get(answer.questionId());
            if (question == null) {
                throw ApiException.fieldInvalid(path + ".questionId",
                        "이 설문의 문항이 아닙니다: " + answer.questionId());
            }
            if (!seen.add(answer.questionId())) {
                throw ApiException.fieldInvalid(path + ".questionId", "같은 문항에 답이 두 번 실렸습니다.");
            }
            Submission submission = interpret(question, answer, path, allowedOptions);
            if (submission != null) {
                submissions.add(submission);
            }
        }

        for (SurveyQuestion question : questionRows) {
            if (question.isRequired() && !answeredIn(submissions, question.getId())) {
                throw ApiException.fieldInvalid("answers",
                        "필수 문항에 답해주세요: " + question.getId());
            }
        }
        return submissions;
    }

    /** {@code null} when the answer is blank — that is "skipped", not "invalid". */
    private Submission interpret(SurveyQuestion question, AnswerCommand answer, String path,
                                 Map<Long, Set<Long>> allowedOptions) {
        SurveyQuestionType type = question.getQuestionType();
        if (type.isChoice()) {
            rejectForeignKeys(answer, path, "selectedOptionIds");
            List<Long> picked = answer.selectedOptionIds();
            if (picked.isEmpty()) {
                return null;
            }
            LinkedHashSet<Long> distinct = new LinkedHashSet<>(picked);
            if (distinct.size() != picked.size()) {
                throw ApiException.fieldInvalid(path + ".selectedOptionIds",
                        "같은 선택지를 두 번 골랐습니다.");
            }
            if (type == SurveyQuestionType.SINGLE_CHOICE && distinct.size() > 1) {
                throw ApiException.fieldInvalid(path + ".selectedOptionIds",
                        "이 문항은 하나만 고를 수 있습니다.");
            }
            Set<Long> allowed = allowedOptions.getOrDefault(question.getId(), Set.of());
            for (Long optionId : distinct) {
                if (!allowed.contains(optionId)) {
                    throw ApiException.fieldInvalid(path + ".selectedOptionIds",
                            "이 문항의 선택지가 아닙니다: " + optionId);
                }
            }
            return Submission.choice(question, List.copyOf(distinct));
        }

        if (type.isRating()) {
            rejectForeignKeys(answer, path, "rating");
            if (answer.rating() == null) {
                return null;
            }
            if (!question.acceptsRating(answer.rating())) {
                throw ApiException.fieldInvalid(path + ".rating",
                        "별점은 " + question.getRatingMin() + "~" + question.getRatingMax()
                                + " 안이어야 합니다.");
            }
            return Submission.rating(question, answer.rating().shortValue());
        }

        rejectForeignKeys(answer, path, "text");
        if (answer.text() == null || answer.text().isBlank()) {
            return null;
        }
        String text = answer.text().trim();
        if (text.length() > type.maxTextLength()) {
            throw ApiException.fieldInvalid(path + ".text",
                    "답변은 " + type.maxTextLength() + "자를 넘을 수 없습니다.");
        }
        return Submission.text(question, text);
    }

    /**
     * A key that does not belong to this question type is a mismatch, not something to ignore.
     *
     * <p>Dropping it silently would let a frontend that changed a question type keep sending the old
     * shape and read every submission as accepted, with the answer quietly missing from the results.
     */
    private void rejectForeignKeys(AnswerCommand answer, String path, String expected) {
        if (!"selectedOptionIds".equals(expected) && !answer.selectedOptionIds().isEmpty()) {
            throw ApiException.fieldInvalid(path + ".selectedOptionIds",
                    "이 문항은 선택지로 답하지 않습니다.");
        }
        if (!"rating".equals(expected) && answer.rating() != null) {
            throw ApiException.fieldInvalid(path + ".rating", "이 문항은 별점으로 답하지 않습니다.");
        }
        if (!"text".equals(expected) && answer.text() != null && !answer.text().isBlank()) {
            throw ApiException.fieldInvalid(path + ".text", "이 문항은 텍스트로 답하지 않습니다.");
        }
    }

    private Map<Long, Set<Long>> allowedOptionIds(List<SurveyQuestion> questionRows) {
        List<Long> choiceIds = questionRows.stream()
                .filter(question -> question.getQuestionType().isChoice())
                .map(SurveyQuestion::getId)
                .toList();
        if (choiceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> allowed = new LinkedHashMap<>();
        options.findByQuestionIdInOrderByQuestionIdAscDisplayOrderAsc(choiceIds)
                .forEach(option -> allowed
                        .computeIfAbsent(option.getQuestionId(), key -> new LinkedHashSet<>())
                        .add(option.getId()));
        return allowed;
    }

    private static boolean answeredIn(List<Submission> submissions, Long questionId) {
        return submissions.stream().anyMatch(s -> s.question().getId().equals(questionId));
    }

    /**
     * Stable per business action: the same member answering the same survey is the same grant, no
     * matter how many times the request arrives (spec 003 FR-007). Never a timestamp.
     */
    static String rewardKey(Long surveyId, Long userId) {
        return CoinReason.SURVEY_REWARD + ":" + surveyId + ":" + userId;
    }

    // ── 요청·응답 ───────────────────────────────────────────────────────────

    public record SubmitCommand(List<AnswerCommand> answers) {

        public SubmitCommand {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }

    /** Exactly one of the three answer keys carries a value; the others must be absent. */
    public record AnswerCommand(Long questionId, List<Long> selectedOptionIds, Integer rating,
                                String text) {

        public AnswerCommand {
            selectedOptionIds = selectedOptionIds == null ? List.of() : List.copyOf(selectedOptionIds);
        }
    }

    /** {@code rewardedCoin} is always present; {@code 0} means nothing was paid (계약 §1). */
    public record SubmitResult(Long responseId, int rewardedCoin) {
    }

    /**
     * Who is answering, resolved from the token and never from the request (헌법 16조).
     *
     * <p>Exactly one field is set, mirroring {@code ck_survey_responses_respondent}.
     */
    public record Respondent(Long userId, String guestKey) {

        public static Respondent member(Long userId) {
            return new Respondent(userId, null);
        }

        public static Respondent guest(String subject) {
            return new Respondent(null, subject);
        }

        public boolean isMember() {
            return userId != null;
        }

        public boolean isGuest() {
            return userId == null;
        }

        SurveyResponse newResponse(Long surveyId, Instant now) {
            return isMember()
                    ? SurveyResponse.byMember(surveyId, userId, now)
                    : SurveyResponse.byGuest(surveyId, guestKey, now);
        }
    }

    /** One validated answer, ready to store. */
    private record Submission(SurveyQuestion question, List<Long> optionIds, short rating, String text) {

        static Submission choice(SurveyQuestion question, List<Long> optionIds) {
            return new Submission(question, optionIds, (short) 0, null);
        }

        static Submission rating(SurveyQuestion question, short value) {
            return new Submission(question, List.of(), value, null);
        }

        static Submission text(SurveyQuestion question, String text) {
            return new Submission(question, List.of(), (short) 0, text);
        }
    }
}
