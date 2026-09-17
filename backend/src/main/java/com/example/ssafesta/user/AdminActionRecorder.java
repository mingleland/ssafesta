package com.example.ssafesta.user;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit row for an administrator action (S15P21A604-743).
 *
 * <p><b>Inside the caller's transaction, never beside it.</b> {@code Propagation.MANDATORY} says so
 * out loud: an audit row that commits while the change it describes rolls back is worse than no
 * audit at all, and one written before the change is a record of something that may never have
 * happened. Joining the caller means the two land together or not at all.
 *
 * <p>Actions are written as plain strings rather than an enum. The vocabulary grows with every
 * block of S15P21A604-742 ({@code SUSPEND}, {@code COIN_ADJUST}, {@code BOOTH_EDIT}, …), and an
 * enum would put a migration-shaped decision in the way of adding one. The constants below are the
 * ones this block uses.
 */
@Component
public class AdminActionRecorder {

    public static final String ADMIN_GRANT = "ADMIN_GRANT";
    public static final String ADMIN_REVOKE = "ADMIN_REVOKE";
    public static final String COIN_ADJUST = "COIN_ADJUST";
    public static final String SUSPEND = "SUSPEND";
    public static final String UNSUSPEND = "UNSUSPEND";
    public static final String BOOTH_EDIT = "BOOTH_EDIT";
    public static final String BOOTH_UNPUBLISH = "BOOTH_UNPUBLISH";
    public static final String PRIZE_CREATE = "PRIZE_CREATE";
    public static final String PRIZE_UPDATE = "PRIZE_UPDATE";
    public static final String PRIZE_FULFILLMENT_UPDATE = "PRIZE_FULFILLMENT_UPDATE";

    public static final String TARGET_USER = "USER";
    public static final String TARGET_BOOTH = "BOOTH";
    public static final String TARGET_EVENT_PRIZE = "EVENT_PRIZE";
    public static final String TARGET_EVENT_PURCHASE = "EVENT_PURCHASE";

    private final AdminActionRepository actions;

    public AdminActionRecorder(AdminActionRepository actions) {
        this.actions = actions;
    }

    /**
     * @param detail why, in the administrator's words where they gave one — this column is what
     *               makes the row readable a month later, so callers pass the reason rather than
     *               restating the action
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAction record(Long actorUserId, String action, String targetType, Long targetId, String detail) {
        return actions.save(new AdminAction(actorUserId, action, targetType, targetId, detail));
    }
}
