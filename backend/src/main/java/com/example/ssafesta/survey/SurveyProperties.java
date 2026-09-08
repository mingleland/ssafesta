package com.example.ssafesta.survey;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The three caps spec 010 leaves to 기획 (C-01 · C-02), plus one timing bound that is ours.
 *
 * <p>Configurable rather than constants because they are <b>undecided</b>, not because anyone
 * expects to tune them per environment. `docs/26` rows 20·21 track the open questions; when they
 * close this becomes one line of yml instead of a code change. Everything else the validator
 * enforces — title 200, prompt 500, rating 1..10, text 200/2000 — comes from a column width or a
 * screen and lives as a constant beside the rule (헌법 30조).
 *
 * <p>The frontend Builder enforces <b>no</b> caps at all, so these are the first limit a survey
 * meets. That is why the refusal carries the field path rather than a bare message: the operator
 * needs to know which question is over the line.
 *
 * @param maxQuestions   questions per survey
 * @param maxOptions     options per choice question
 * @param maxRewardCoin  reward coin per response; `docs/01` §8 floats 5~10 and this is the top of it
 * @param submitTimeoutSeconds hard ceiling on one submission's transaction, applied as the JDBC
 *                       query timeout. Seconds rather than a {@code Duration} because
 *                       {@code @Transactional(timeoutString = ...)} resolves a placeholder and parses
 *                       it as seconds. Without this ceiling a submission can wait on the booth lock
 *                       indefinitely, and then no grace below is long enough to be true
 * @param guestKeyGrace  how long after a guest token stops being accepted {@code
 *                       SurveyGuestKeySweeper} still leaves its duplicate-guard key alone. Must
 *                       exceed {@code submitTimeout} — that is what makes it a bound rather than a
 *                       hope, and the constructor refuses the configuration otherwise
 */
@ConfigurationProperties("app.survey")
public record SurveyProperties(int maxQuestions, int maxOptions, int maxRewardCoin,
                               int submitTimeoutSeconds, Duration guestKeyGrace) {

    public SurveyProperties {
        // 0 이하로 조용히 뜨면 모든 저장이 400 이 되고, 화면에는 "문항이 너무 많습니다" 만
        // 보여 원인이 설정이라는 것을 알 방법이 없다.
        if (maxQuestions < 1) {
            throw new IllegalStateException("app.survey.max-questions 은 1 이상이어야 합니다: " + maxQuestions);
        }
        if (maxOptions < 2) {
            throw new IllegalStateException(
                    "app.survey.max-options 은 2 이상이어야 합니다 — 선택형 문항은 선택지 2개가 최소다: " + maxOptions);
        }
        if (maxRewardCoin < 0) {
            throw new IllegalStateException("app.survey.max-reward-coin 은 0 이상이어야 합니다: " + maxRewardCoin);
        }
        if (submitTimeoutSeconds < 1) {
            throw new IllegalStateException(
                    "app.survey.submit-timeout-seconds 는 1 이상이어야 합니다: " + submitTimeoutSeconds);
        }
        // 0 이면 실행 중이던 요청이 중복 방지 없이 통과하는 창이 다시 열린다.
        if (guestKeyGrace == null || guestKeyGrace.isNegative() || guestKeyGrace.isZero()) {
            throw new IllegalStateException(
                    "app.survey.guest-key-grace 는 0보다 커야 합니다: " + guestKeyGrace);
        }
        // 유예가 제출 상한보다 짧으면 sweeper 가 아직 처리 중인 요청의 중복 방지 키를 지울 수
        // 있다. 이 검사가 유예를 "넉넉해 보이는 값" 이 아니라 강제되는 경계로 만든다.
        //
        // submitTimeout() 을 부르지 않는다 — compact constructor 안에서는 필드가 아직 대입되기
        // 전이라 그 접근자가 0 을 돌려주고, 이 검사가 조용히 통과한다(처음에 그렇게 썼다).
        Duration ceiling = Duration.ofSeconds(submitTimeoutSeconds);
        if (guestKeyGrace.compareTo(ceiling) <= 0) {
            throw new IllegalStateException("app.survey.guest-key-grace 는 submit-timeout-seconds 보다 "
                    + "길어야 합니다 — 그래야 실행 중인 제출이 끝난 뒤에 키가 지워집니다: "
                    + guestKeyGrace + " <= " + ceiling);
        }
    }

    /** The submission ceiling as a {@link Duration}, for comparing against {@link #guestKeyGrace}. */
    public Duration submitTimeout() {
        return Duration.ofSeconds(submitTimeoutSeconds);
    }
}
