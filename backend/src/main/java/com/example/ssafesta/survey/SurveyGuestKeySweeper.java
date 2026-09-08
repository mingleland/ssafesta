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
 * <p><b>1인 1응답 is not weakened.</b> Clearing happens at the token's own {@code exp}, and a guest
 * token cannot be refreshed ({@code /auth/refresh} renews member sessions only) — so the session
 * whose key is cleared is already dead and could not submit again either way. What comes back later
 * is a new token with a new subject, which this system has always counted as a different person
 * (계약 §6).
 */
@Component
class SurveyGuestKeySweeper {

    private static final Logger log = LoggerFactory.getLogger(SurveyGuestKeySweeper.class);

    /**
     * Rows already cleared are skipped by the {@code NOT LIKE} — without it every pass would rewrite
     * the same rows to the same values forever. The second bound is the pre-V23 fallback.
     */
    private static final String CLEAR = """
            UPDATE survey_responses
               SET respondent_guest_key = 'expired:' || id
             WHERE respondent_guest_key IS NOT NULL
               AND respondent_guest_key NOT LIKE 'expired:%'
               AND (respondent_session_expires_at < ?
                    OR (respondent_session_expires_at IS NULL AND submitted_at < ?))
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
     * <p><b>The session's own {@code exp} decides, not a guess.</b> Estimating the moment as
     * {@code submitted_at + access-token-ttl} is wrong in both directions: the token was issued
     * before the answer arrived, so it fires up to one TTL late; and shortening the TTL makes it
     * fire while tokens issued under the old one are still valid, which lets the same token answer
     * the same survey twice — a cleared key is no longer in {@code ux_survey_responses_guest}.
     *
     * <p>That estimate survives for rows written before V23, which have no expiry recorded. It is
     * never <i>early</i> under a steady configuration, and no such rows are being created any more.
     */
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void clearExpiredGuestKeys() {
        Instant now = Instant.now();
        int cleared = jdbc.update(CLEAR, Timestamp.from(now),
                Timestamp.from(now.minus(auth.accessTokenTtl())));
        if (cleared > 0) {
            log.info("만료된 게스트 세션 식별자 {}건을 설문 응답에서 지웠습니다 (헌법 12조).", cleared);
        }
    }
}
