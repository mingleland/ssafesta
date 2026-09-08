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
 * @param guestKeyGrace  how long after a guest token stops being accepted {@code
 *                       SurveyGuestKeySweeper} still leaves its duplicate-guard key alone. This one
 *                       is not a 기획 question — see the sweeper for why a request that authenticated
 *                       before the token expired can still be running afterwards
 */
@ConfigurationProperties("app.survey")
public record SurveyProperties(int maxQuestions, int maxOptions, int maxRewardCoin,
                               Duration guestKeyGrace) {

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
        // 0 이면 실행 중이던 요청이 중복 방지 없이 통과하는 창이 다시 열린다.
        if (guestKeyGrace == null || guestKeyGrace.isNegative() || guestKeyGrace.isZero()) {
            throw new IllegalStateException(
                    "app.survey.guest-key-grace 는 0보다 커야 합니다: " + guestKeyGrace);
        }
    }
}
