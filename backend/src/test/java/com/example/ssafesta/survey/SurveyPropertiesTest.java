package com.example.ssafesta.survey;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 설정이 서로 어긋난 채로 기동하지 않는지 (spec 010, 헌법 12조).
 *
 * <p>특히 {@code guestKeyGrace > submitTimeout} 은 <b>주석이 아니라 검사</b>여야 한다. 그 관계가
 * 깨지면 sweeper 가 아직 처리 중인 제출의 중복 방지 키를 지울 수 있고, 그때 나타나는 증상은 "게스트가
 * 두 번 답했다" 뿐이라 설정이 원인이라는 것을 알아낼 방법이 없다.
 */
class SurveyPropertiesTest {

    private static final Duration GRACE = Duration.ofMinutes(10);

    @Test
    void theGraceMustOutlastASubmission() {
        assertThatThrownBy(() -> new SurveyProperties(30, 10, 10, 600, GRACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guest-key-grace");

        // 같아도 안 된다 — 제출이 상한을 꽉 채우면 그 순간 키가 사라진다.
        assertThatThrownBy(() -> new SurveyProperties(30, 10, 10, 600, Duration.ofSeconds(600)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSubmissionCeilingIsRequired() {
        assertThatThrownBy(() -> new SurveyProperties(30, 10, 10, 0, GRACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submit-timeout-seconds");
    }

    @Test
    void theCapsStillRefuseNonsense() {
        assertThatThrownBy(() -> new SurveyProperties(0, 10, 10, 30, GRACE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-questions");
        assertThatThrownBy(() -> new SurveyProperties(30, 1, 10, 30, GRACE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-options");
        assertThatThrownBy(() -> new SurveyProperties(30, 10, -1, 30, GRACE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-reward-coin");
    }
}
