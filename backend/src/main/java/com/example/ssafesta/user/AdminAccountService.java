package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrator roster and the two ways it changes (S15P21A604-743).
 *
 * <p>Both changes are audited in the same transaction. Suspension and coin movements each keep
 * their own history; leaving privilege changes out would make "who made this person an
 * administrator?" the one question the trail cannot answer.
 */
@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final UserRepository users;
    private final AdminGuard guard;
    private final AdminActionRecorder audit;

    public AdminAccountService(UserRepository users, AdminGuard guard, AdminActionRecorder audit) {
        this.users = users;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdminView> list() {
        return users.findByAccountTypeOrderByIdAsc(User.ADMIN).stream().map(AdminView::of).toList();
    }

    /**
     * Grants admin rights to a member.
     *
     * @throws ApiException {@code USER_NOT_FOUND} no such member, {@code ADMIN_ALREADY} they
     *                      already have them, {@code FORBIDDEN} the account is suspended
     */
    @Transactional
    public AdminView promote(Long targetUserId, Long actorUserId, String note) {
        User target = require(targetUserId);
        if (target.getStatus() != AccountStatus.ACTIVE) {
            // A suspended account cannot hold a session, so promoting it grants nothing today and
            // hands out administrator rights the moment somebody lifts the suspension for an
            // unrelated reason.
            throw new ApiException(ErrorCode.FORBIDDEN, "정지된 계정은 관리자로 승격할 수 없습니다.");
        }
        if (!target.promoteToAdmin()) {
            throw new ApiException(ErrorCode.ADMIN_ALREADY);
        }
        audit.record(actorUserId, AdminActionRecorder.ADMIN_GRANT, AdminActionRecorder.TARGET_USER,
                targetUserId, note);
        log.info("관리자 승격 — targetUserId={}, actorUserId={}", targetUserId, actorUserId);
        return AdminView.of(target);
    }

    /**
     * Takes admin rights away. Takes effect on the target's next request — the judgement is a
     * database read, not a token claim.
     *
     * <p>Demoting someone who is not an administrator succeeds and changes nothing: {@code DELETE}
     * says what the caller wanted the world to look like afterwards, and it already does.
     *
     * @throws ApiException {@code USER_NOT_FOUND} no such member, {@code MASTER_PROTECTED} the
     *                      master account, {@code ADMIN_LAST_ONE} the only administrator left
     */
    @Transactional
    public void demote(Long targetUserId, Long actorUserId, String note) {
        User target = require(targetUserId);
        guard.requireTargetNotMaster(targetUserId);
        if (!target.isAdmin()) {
            return;
        }
        // The promotion endpoint is itself behind the admin gate, so an empty roster cannot be
        // refilled through the API at all — recovery would be a migration.
        if (users.countByAccountType(User.ADMIN) <= 1) {
            throw new ApiException(ErrorCode.ADMIN_LAST_ONE);
        }
        target.demoteToMember();
        audit.record(actorUserId, AdminActionRecorder.ADMIN_REVOKE, AdminActionRecorder.TARGET_USER,
                targetUserId, note);
        log.info("관리자 강등 — targetUserId={}, actorUserId={}", targetUserId, actorUserId);
    }

    private User require(Long userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    /** @param master the protected account — the console greys out every action on this row */
    public record AdminView(Long userId, String nickname, boolean master) {
        static AdminView of(User user) {
            return new AdminView(user.getId(), user.getNickname(), user.isMaster());
        }
    }
}
