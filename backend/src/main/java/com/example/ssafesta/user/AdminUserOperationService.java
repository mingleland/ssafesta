package com.example.ssafesta.user;

import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies administrator account status changes and their audit rows atomically. */
@Service
public class AdminUserOperationService {

    private final UserRepository users;
    private final AdminGuard guard;
    private final AccountStatusHistoryRepository histories;
    private final AdminActionRecorder audit;
    private final MemberSessionService sessions;

    public AdminUserOperationService(UserRepository users,
                                     AdminGuard guard,
                                     AccountStatusHistoryRepository histories,
                                     AdminActionRecorder audit,
                                     MemberSessionService sessions) {
        this.users = users;
        this.guard = guard;
        this.histories = histories;
        this.audit = audit;
        this.sessions = sessions;
    }

    @Transactional
    public void suspend(Long targetUserId, Long actorUserId, String reason) {
        change(targetUserId, actorUserId, AccountStatus.SUSPENDED, reason, AdminActionRecorder.SUSPEND);
    }

    @Transactional
    public void unsuspend(Long targetUserId, Long actorUserId) {
        change(targetUserId, actorUserId, AccountStatus.ACTIVE, "ADMIN_UNSUSPEND",
                AdminActionRecorder.UNSUSPEND);
    }

    @Transactional(readOnly = true)
    public Page<AccountStatusHistoryView> history(Long targetUserId, Pageable pageable) {
        if (!users.existsById(targetUserId)) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        return histories.findViewsByUserId(targetUserId, pageable);
    }

    private void change(Long targetUserId, Long actorUserId, AccountStatus targetStatus,
                        String reason, String action) {
        // Every path that can reduce the active administrator set uses this order.
        var activeAdmins = users.findByAccountTypeAndStatusForUpdate(User.ADMIN, AccountStatus.ACTIVE);
        User target = users.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        guard.requireTargetNotMaster(targetUserId);
        if (target.getStatus() == targetStatus) {
            return;
        }
        if (targetStatus == AccountStatus.SUSPENDED
                && target.isAdmin()
                && target.getStatus() == AccountStatus.ACTIVE
                && activeAdmins.size() <= 1) {
            throw new ApiException(ErrorCode.ADMIN_LAST_ONE);
        }

        AccountStatus previous = target.getStatus();
        if (targetStatus == AccountStatus.SUSPENDED) {
            target.suspend(reason);
        } else {
            target.unsuspend();
        }
        histories.save(new AccountStatusHistory(target, previous, targetStatus, reason, actorUserId));
        audit.record(actorUserId, action, AdminActionRecorder.TARGET_USER, targetUserId, reason);
        sessions.revoke(targetUserId);
    }
}
