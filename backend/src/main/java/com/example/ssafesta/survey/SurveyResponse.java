package com.example.ssafesta.survey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One submission (spec 010 FR-004, FR-005).
 *
 * <p><b>Member xor guest.</b> V1 required {@code respondent_user_id}, which made a guest response
 * structurally impossible — guests have no {@code users} row (헌법 12조). V22 relaxes the column and
 * adds {@code respondent_guest_key} under a CHECK that exactly one of the two is set: a row with
 * neither is a response with no author, and a row with both has two.
 *
 * <p>One response per respondent is two partial unique indexes rather than one plain index, because
 * a plain {@code UNIQUE(survey_id, respondent_user_id)} treats every {@code NULL} as distinct and
 * would let all guest responses skip the check entirely.
 *
 * <p>{@code rewardLedgerEntryId} is the second guard on paying once. The idempotency key on
 * {@code WalletService.credit} is the first; this column is {@code UNIQUE}, so even a second ledger
 * entry could not be attached here. Guest responses leave it {@code null} — guests are refused on
 * rewarded surveys before they get this far.
 *
 * <p>No {@code respondent} field ever leaves this class. Results are anonymous by construction
 * (FR-009, SC-003) rather than by a flag someone could forget to check.
 */
@Entity
@Table(name = "survey_responses")
public class SurveyResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false, updatable = false)
    private Long surveyId;

    /** Set for a member, {@code null} for a guest. */
    @Column(name = "respondent_user_id", updatable = false)
    private Long respondentUserId;

    /** The access token subject ({@code guest:<uuid>}) for a guest, {@code null} for a member. */
    @Column(name = "respondent_guest_key", length = 100, updatable = false)
    private String respondentGuestKey;

    /**
     * When the guest session that produced this response expires — the token's own {@code exp},
     * copied at submission (V23).
     *
     * <p>{@code SurveyGuestKeySweeper} clears {@link #respondentGuestKey} at this instant instead of
     * guessing it from {@code submittedAt} plus the configured TTL. The guess is wrong both ways: it
     * fires up to one TTL late, because the token was issued before the answer arrived, and a
     * shortened TTL makes it fire while the token is still valid — which would let that same token
     * answer again, a cleared key having left {@code ux_survey_responses_guest}.
     *
     * <p>{@code null} for members, who have no session expiry, and for guest rows written before
     * V23; the sweeper keeps the old estimate for those.
     */
    @Column(name = "respondent_session_expires_at", updatable = false)
    private Instant respondentSessionExpiresAt;

    @Column(name = "reward_ledger_entry_id")
    private Long rewardLedgerEntryId;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected SurveyResponse() {
    }

    private SurveyResponse(Long surveyId, Long respondentUserId, String respondentGuestKey,
                           Instant sessionExpiresAt, Instant now) {
        this.surveyId = surveyId;
        this.respondentUserId = respondentUserId;
        this.respondentGuestKey = respondentGuestKey;
        this.respondentSessionExpiresAt = sessionExpiresAt;
        this.submittedAt = now;
    }

    public static SurveyResponse byMember(Long surveyId, Long userId, Instant now) {
        return new SurveyResponse(surveyId, userId, null, null, now);
    }

    /**
     * @param sessionExpiresAt the guest token's {@code exp}; {@code null} only if the token carried
     *                         none, and then the sweeper falls back to its estimate
     */
    public static SurveyResponse byGuest(Long surveyId, String guestKey, Instant sessionExpiresAt,
                                         Instant now) {
        return new SurveyResponse(surveyId, null, guestKey, sessionExpiresAt, now);
    }

    /** Called only after {@code WalletService.credit} returned an entry id. */
    public void linkReward(Long ledgerEntryId) {
        this.rewardLedgerEntryId = ledgerEntryId;
    }

    public Long getId() {
        return id;
    }

    public Long getSurveyId() {
        return surveyId;
    }

    /**
     * {@code null} for a guest. Exposed only for the admin event-entrant path
     * ({@code AdminEventSurveyService}) — every other reader of a response gets the anonymous shape
     * (FR-009, SC-003), and this getter is what that boundary is drawn around.
     */
    public Long getRespondentUserId() {
        return respondentUserId;
    }

    public Long getRewardLedgerEntryId() {
        return rewardLedgerEntryId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
