package com.example.ssafesta.user;

import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final OAuthIdentityRepository identities;

    public AdminUserOperationService(UserRepository users,
                                     AdminGuard guard,
                                     AccountStatusHistoryRepository histories,
                                     AdminActionRecorder audit,
                                     MemberSessionService sessions,
                                     OAuthIdentityRepository identities) {
        this.users = users;
        this.guard = guard;
        this.histories = histories;
        this.audit = audit;
        this.sessions = sessions;
        this.identities = identities;
    }

    /**
     * The admin console's member search (S15P21A604-832) — every admin API downstream needs a
     * {@code userId}, and there was no way to find one. A blank {@code query} lists the roster;
     * a numeric one also matches an exact id, so an operator can paste either a nickname
     * fragment or an id copied from an error log.
     */
    @Transactional(readOnly = true)
    public Page<AdminUserSummaryView> search(String query, Pageable pageable) {
        String trimmed = query == null ? "" : query.trim();
        Long exactId = trimmed.matches("\\d+") ? Long.parseLong(trimmed) : null;
        return summariesOf(users.search(exactId, trimmed, pageable));
    }

    @Transactional(readOnly = true)
    public AdminUserSummaryView detail(Long targetUserId) {
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ADMIN_TARGET_NOT_FOUND));
        return summaryOf(target);
    }

    private AdminUserSummaryView summaryOf(User user) {
        List<String> providers = identities.findAllByUser_Id(user.getId()).stream()
                .map(identity -> identity.getProvider().name()).toList();
        return new AdminUserSummaryView(user.getId(), user.getNickname(), user.getStatus().name(),
                user.isAdmin(), user.isMaster(), providers, user.getCreatedAt());
    }

    /**
     * The same summary, batched for a page of rows — one {@code IN} query for providers instead
     * of one per row.
     */
    private Page<AdminUserSummaryView> summariesOf(Page<User> page) {
        Map<Long, List<String>> providersByUser = identities
                .findAllByUser_IdIn(page.getContent().stream().map(User::getId).toList()).stream()
                .collect(Collectors.groupingBy(identity -> identity.getUser().getId(),
                        Collectors.mapping(identity -> identity.getProvider().name(), Collectors.toList())));
        return page.map(user -> new AdminUserSummaryView(user.getId(), user.getNickname(),
                user.getStatus().name(), user.isAdmin(), user.isMaster(),
                providersByUser.getOrDefault(user.getId(), List.of()), user.getCreatedAt()));
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
            throw new ApiException(ErrorCode.ADMIN_TARGET_NOT_FOUND);
        }
        return histories.findViewsByUserId(targetUserId, pageable);
    }

    private void change(Long targetUserId, Long actorUserId, AccountStatus targetStatus,
                        String reason, String action) {
        // Every path that can reduce the active administrator set uses this order.
        var activeAdmins = users.findByAccountTypeAndStatusForUpdate(User.ADMIN, AccountStatus.ACTIVE);
        User target = users.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ADMIN_TARGET_NOT_FOUND));
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

    public record AdminUserSummaryView(Long userId, String nickname, String status, boolean admin,
                                       boolean master, List<String> providers, Instant joinedAt) { }
}
