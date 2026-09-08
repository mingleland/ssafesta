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
 * Drops the guest session's traces from a survey response once that session can no longer be used
 * (헌법 12조 — "게스트 세션은 둘러보기 전용이며 종료 시 데이터를 삭제한다").
 *
 * <p><b>The answers stay; only the session goes.</b> A response belongs to the booth that collected
 * it — deleting the row would let one visitor's session ending erase an operator's result. What 12조
 * asks to delete is the guest's own session data, and on a response that is two columns: the access
 * token subject and the session's expiry.
 *
 * <p><b>Both columns, not just the key.</b> Every response from one token carries the same
 * {@code exp} to the microsecond, so an expiry left behind still groups one person's answers across
 * surveys — that linkage is what 12조 is about, and dropping only its name does not remove it.
 *
 * <p><b>The key is replaced rather than nulled, so no migration is needed for it.</b>
 * {@code ck_survey_responses_respondent} requires exactly one of member id / guest key, and
 * {@code ux_survey_responses_guest} is unique over the non-null keys. Writing
 * {@code expired:<response id>} satisfies both — unique by construction, and carrying nothing about
 * the person, being derived from the row itself.
 *
 * <p><b>1인 1응답 is not weakened.</b> Clearing waits for the token's {@code exp} <i>plus</i> the
 * decoder's clock skew, so the session whose key goes is one no request can still authenticate
 * with. A guest token cannot be refreshed ({@code /auth/refresh} renews member sessions only), and
 * what comes back later is a new token with a new subject — which this system has always counted as
 * a different person (계약 §6).
 */
@Component
class SurveyGuestKeySweeper {

    private static final Logger log = LoggerFactory.getLogger(SurveyGuestKeySweeper.class);

    /**
     * Rows already cleared drop out on their own — their expiry is {@code NULL} and no comparison
     * matches it, which is also why the index behind this can shrink. Member rows never carry an
     * expiry, so the guest-key test is a guard against a future writer setting one where it does not
     * belong rather than a filter this pass needs.
     */
    private static final String CLEAR = """
            UPDATE survey_responses
               SET respondent_guest_key = 'expired:' || id,
                   respondent_session_expires_at = NULL
             WHERE respondent_guest_key IS NOT NULL
               AND respondent_session_expires_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final AuthProperties auth;
    private final SurveyProperties survey;

    SurveyGuestKeySweeper(JdbcTemplate jdbc, AuthProperties auth, SurveyProperties survey) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.survey = survey;
    }

    /**
     * One pass every five minutes.
     *
     * <p><b>The session's own {@code exp} decides, not a guess.</b> Estimating the moment as
     * {@code submitted_at + access-token-ttl} was wrong in both directions: the token is issued
     * before the answer arrives, so it fired up to one TTL late; and shortening the TTL made it fire
     * while tokens issued under the old one were still valid. V23 records the real {@code exp}, and
     * backfilled the rows that predate it with that same upper bound.
     *
     * <p><b>And it waits out the clock skew.</b> The decoder accepts a token until
     * {@code exp + app.auth.jwt-clock-skew} ({@code JwtTimestampValidator}), so clearing at
     * {@code exp} leaves a window where a request still authenticates while its duplicate-guard key
     * is already gone — that same token could then answer the same survey twice. A request
     * closes the first: a token past {@code exp + skew} cannot start a new request.
     *
     * <p><b>And it waits out requests already running.</b> A request authenticated just before
     * {@code exp} can still be in the server — blocked on the booth lock, say — after the skew has
     * passed. Clearing its guard key before it commits lets it insert a duplicate. So the cut is
     * pushed back by {@code app.survey.guest-key-grace} as well. <b>That is a bound, not a proof</b>:
     * it is sized well past how long a request can live, and the alternative — keeping the
     * identifier until nothing could conceivably be running — is keeping it forever, which is the
     * thing 12조 forbids.
     */
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void clearExpiredGuestKeys() {
        Instant unusableBefore = Instant.now()
                .minus(auth.jwtClockSkew())
                .minus(survey.guestKeyGrace());
        int cleared = jdbc.update(CLEAR, Timestamp.from(unusableBefore));
        if (cleared > 0) {
            log.info("만료된 게스트 세션 흔적 {}건을 설문 응답에서 지웠습니다 (헌법 12조).", cleared);
        }
    }
}
