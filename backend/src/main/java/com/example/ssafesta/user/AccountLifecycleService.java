package com.example.ssafesta.user;

import com.example.ssafesta.auth.MemberSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountLifecycleService {
    private final UserRepository users; private final AccountStatusHistoryRepository histories; private final MemberSessionService sessions; private final AccountDeletionService deletionService;
    public AccountLifecycleService(UserRepository users, AccountStatusHistoryRepository histories, MemberSessionService sessions, AccountDeletionService deletionService) { this.users=users; this.histories=histories; this.sessions=sessions; this.deletionService=deletionService; }
    @Transactional public void withdraw(Long userId) { sessions.revoke(userId); deletionService.deleteUserGraph(userId); }
    @Transactional public void suspend(Long userId, Long adminId, String reason) { change(userId, adminId, AccountStatus.SUSPENDED, reason); }
    @Transactional public void unsuspend(Long userId, Long adminId) { change(userId, adminId, AccountStatus.ACTIVE, "ADMIN_UNSUSPEND"); }
    private void change(Long userId, Long actorId, AccountStatus target, String reason) {
        User user=users.findById(userId).orElseThrow(); AccountStatus previous=user.getStatus();
        if (target==AccountStatus.SUSPENDED) user.suspend(reason); else user.unsuspend();
        histories.save(new AccountStatusHistory(user, previous, target, reason, actorId)); sessions.revoke(userId);
    }
}
