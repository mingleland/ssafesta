package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may act as an administrator, and who may not be acted upon (S15P21A604-743).
 *
 * <p><b>The token does not carry it.</b> The {@code role} claim stays {@code MEMBER} for
 * administrators: {@link MemberPrincipal} judges on {@code "MEMBER".equals(role)} and twenty-odd
 * controllers route through it, so issuing {@code ADMIN} there would make an administrator read as
 * a guest and shut them out of their own wallet and booth with {@code 403 MEMBER_ONLY}. Asking the
 * database instead costs one row read on a path nobody calls in a loop, and buys the property that
 * matters more: a demotion takes effect on the next request rather than when a token expires.
 */
@Component
public class AdminGuard {

    private final UserRepository users;

    public AdminGuard(UserRepository users) {
        this.users = users;
    }

    /**
     * The administrator behind this request.
     *
     * @return their member id, so callers can record it as the actor
     * @throws ApiException {@code MEMBER_ONLY} for a guest token, {@code FORBIDDEN} for a member
     *                      who is not an administrator
     */
    @Transactional(readOnly = true)
    public Long requireAdmin(Jwt jwt) {
        Long userId = MemberPrincipal.requireMemberId(jwt);
        requireAdmin(userId);
        return userId;
    }

    /**
     * The same judgement for callers that already hold a member id — the booth and staff guards
     * reach this overload from inside their own owner checks.
     *
     * <p>A member id with no row answers {@code FORBIDDEN} rather than {@code USER_NOT_FOUND}: a
     * token whose account is gone has no business here either, and the distinction would only tell
     * the holder which of the two it was.
     */
    @Transactional(readOnly = true)
    public User requireAdmin(Long userId) {
        return users.findById(userId)
                .filter(User::isAdmin)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }

    /**
     * A non-throwing authority check for another domain's established error contract.
     *
     * <p>For example, a non-editor on a booth must still receive that domain's
     * {@code BOOTH_EDITOR_FORBIDDEN}, not the generic admin API error. Callers that expose the
     * admin surface itself use {@link #requireAdmin(Long)} instead.
     */
    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        return users.findById(userId).map(User::isAdmin).orElse(false);
    }

    /**
     * Refuses an admin action aimed at the master account.
     *
     * <p>Reading is not acting — member lookups and ledgers still show the master. What this blocks
     * is every action that changes them: demotion, suspension, forced withdrawal, forced logout, a
     * forced nickname change, a coin adjustment, a sanction from a report.
     *
     * <p>A target with no row is left alone here. It is not this guard's refusal to make — the
     * caller answers that with its own {@code USER_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public void requireTargetNotMaster(Long targetUserId) {
        if (targetUserId == null) {
            return;
        }
        users.findById(targetUserId)
                .filter(User::isMaster)
                .ifPresent(master -> {
                    throw new ApiException(ErrorCode.MASTER_PROTECTED);
                });
    }

    /**
     * The same refusal reached through a resource rather than through the person.
     *
     * <p>Without it the protection is theatre: an administrator who cannot suspend the master could
     * still seize their booth, unpublish it, or delete its projects and documents. Booth, game,
     * project and document paths pass the owner's id here before they act.
     */
    @Transactional(readOnly = true)
    public void requireOwnerNotMaster(Long ownerUserId) {
        requireTargetNotMaster(ownerUserId);
    }
}
