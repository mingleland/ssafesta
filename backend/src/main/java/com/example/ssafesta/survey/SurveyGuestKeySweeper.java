package com.example.ssafesta.survey;

import com.example.ssafesta.auth.AuthProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drops the guest session identifier from a survey response once that session can no longer exist
 * (헌법 12조 — "게스트 세션은 둘러보기 전용이며 종료 시 데이터를 삭제한다").
 *
 * <p><b>The answers stay; only the identifier goes.</b> A response belongs to the booth that
 * collected it — deleting the row would let one visitor's session ending erase an operator's
 * result. What 12조 asks to delete is the guest's own session data, and on a response that is
 * exactly one column: {@code respondent_guest_key}, the access token subject.
 *
 * <p><b>Replaced rather than nulled, so no migration is needed.</b>
 * {@code ck_survey_responses_respondent} requires exactly one of member id / guest key, and
 * {@code ux_survey_responses_guest} is unique over the non-null keys. Writing
 * {@code expired:<response id>} satisfies both — it is unique by construction and carries nothing
 * about the person, being derived from the row itself.
 *
 * <p><b>1인 1응답 is not weakened.</b> A guest access token lives {@code app.auth.access-token-ttl}
 * and cannot be refreshed ({@code /auth/refresh} renews member sessions only), so by the time a key
 * is cleared the session that made it is already dead — it could not submit again either way. What
 * comes back later is a new token with a new subject, which this system has always counted as a
 * different person (계약 §6).
 */
@Component
class SurveyGuestKeySweeper {

    private static final Logger log = LoggerFactory.getLogger(SurveyGuestKeySweeper.class);

    /**
     * Rows already cleared are skipped by the {@code NOT LIKE} — without it every pass would rewrite
     * the same rows to the same values forever.
     */
    private static final String CLEAR = """
            UPDATE survey_responses
               SET respondent_guest_key = 'expired:' || id
             WHERE respondent_guest_key IS NOT NULL
               AND respondent_guest_key NOT LIKE 'expired:%'
               AND submitted_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final AuthProperties auth;

    SurveyGuestKeySweeper(JdbcTemplate jdbc, AuthProperties auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    /**
     * One pass every five minutes.
     *
     * <p>The retention is read from {@code app.auth.access-token-ttl} rather than duplicated as a
     * survey setting: the thing being waited out <i>is</i> the guest session, so a second knob could
     * only drift from it. A pass therefore clears keys whose session expired at least one pass ago.
     */
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void clearExpiredGuestKeys() {
        Instant cutoff = Instant.now().minus(auth.accessTokenTtl());
        int cleared = jdbc.update(CLEAR, Timestamp.from(cutoff));
        if (cleared > 0) {
            log.info("만료된 게스트 세션 식별자 {}건을 설문 응답에서 지웠습니다 (헌법 12조).", cleared);
        }
    }
}
