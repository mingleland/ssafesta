package com.example.ssafesta.wallet;

/**
 * Ledger reason codes (spec 003 FR-005).
 *
 * <p>Deliberately constants rather than an enum: later specs (004 lease, 010 survey, 012 shop,
 * 014 minigame) add their own reasons, and they must be able to do so without editing this class.
 * {@code reason_type} is {@code VARCHAR(40)} — {@link #validate} enforces that so an over-long
 * reason fails loudly instead of being truncated by the driver.
 */
public final class CoinReason {

    /** Signup grant, once per member (spec 003 FR-002). */
    public static final String INITIAL_GRANT = "INITIAL_GRANT";

    /** Daily grant, once per member per KST date (spec 003 FR-003). */
    public static final String DAILY_GRANT = "DAILY_GRANT";

    /** Daily-mission claim reward, once per mission per member per KST date (GitLab #233). */
    public static final String DAILY_MISSION = "DAILY_MISSION";

    /** Manual administrator correction (spec 003 FR-013). */
    public static final String ADMIN_ADJUSTMENT = "ADMIN_ADJUSTMENT";

    /** Catalog item purchase (spec 012 FR-003). */
    public static final String PURCHASE = "PURCHASE";

    /** Survey response reward, once per member per survey (spec 010 FR-005). */
    public static final String SURVEY_REWARD = "SURVEY_REWARD";

    /**
     * Minigame reward, once per session (spec 014 FR-006).
     *
     * <p>Also the key the daily cap counts by: today's entries under this reason <i>are</i> the
     * running total (C-04), which is why the value lives here rather than in the minigame package —
     * {@code WalletService.grantedTodayFor} has to be handed the same literal.
     */
    public static final String MINIGAME_REWARD = "MINIGAME_REWARD";

    /**
     * Slot machine stake, charged at the start of a spin (spec 021 FR-004).
     *
     * <p>Deliberately <b>not</b> {@link #MINIGAME_REWARD}'s counterpart: the timing-stop cap counts
     * by reason, so a slot payout recorded under that name would eat a player's timing-stop
     * allowance. Keeping the two games on separate reasons is what keeps the caps separate without
     * either feature knowing about the other (#205 확정값 2).
     */
    public static final String SLOT_BET = "SLOT_BET";

    /** Slot machine payout, granted in the same transaction as {@link #SLOT_BET} (spec 021 FR-004). */
    public static final String SLOT_PAYOUT = "SLOT_PAYOUT";

    /** Booth lease fee, charged to the lessee (spec 004 FR-006). */
    public static final String LEASE_PAYMENT = "LEASE_PAYMENT";

    /** Event-shop prize purchase (S15P21A604-832 후속, GitLab #217). */
    public static final String PRIZE_PURCHASE = "PRIZE_PURCHASE";

    /** {@code reference_type} recorded alongside {@link #PRIZE_PURCHASE}; the id is a prize. */
    public static final String EVENT_PRIZE_REFERENCE_TYPE = "EVENT_PRIZE";

    /** {@code reference_type} recorded alongside {@link #ADMIN_ADJUSTMENT}. */
    public static final String ADMIN_ACTOR_REFERENCE_TYPE = "ADMIN_USER";

    /** {@code reference_type} recorded alongside {@link #LEASE_PAYMENT}; the id is a lease. */
    public static final String LEASE_REFERENCE_TYPE = "BOOTH_LEASE";

    /** {@code reference_type} recorded alongside {@link #SURVEY_REWARD}; the id is a survey. */
    public static final String SURVEY_REFERENCE_TYPE = "SURVEY";

    static final int MAX_LENGTH = 40;

    private CoinReason() {
    }

    static String validate(String reasonType) {
        if (reasonType == null || reasonType.isBlank()) {
            throw new IllegalArgumentException("원장 사유(reasonType)는 비어 있을 수 없습니다.");
        }
        if (reasonType.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "원장 사유는 " + MAX_LENGTH + "자를 넘을 수 없습니다: " + reasonType.length() + "자");
        }
        return reasonType;
    }
}
