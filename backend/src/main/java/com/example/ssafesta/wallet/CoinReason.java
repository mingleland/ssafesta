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

    /** Manual administrator correction (spec 003 FR-013). */
    public static final String ADMIN_ADJUSTMENT = "ADMIN_ADJUSTMENT";

    /** {@code reference_type} recorded alongside {@link #ADMIN_ADJUSTMENT}. */
    public static final String ADMIN_ACTOR_REFERENCE_TYPE = "ADMIN_USER";

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
