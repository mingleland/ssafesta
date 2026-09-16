package com.example.ssafesta.booth;

import com.example.ssafesta.user.AdminActionRecorder;
import com.example.ssafesta.user.AdminGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Performs the admin-only emergency action that removes a booth from visitor publication. */
@Service
public class AdminBoothPublicationService {

    private final BoothRepository booths;
    private final AdminGuard admins;
    private final AdminActionRecorder actions;

    public AdminBoothPublicationService(BoothRepository booths, AdminGuard admins,
                                        AdminActionRecorder actions) {
        this.booths = booths;
        this.admins = admins;
        this.actions = actions;
    }

    /**
     * Removes only the current public pointer. Versions and drafts are evidence and remain intact.
     * The booth row lock serializes this with a normal publish so a stale emergency request cannot
     * erase a version that was published after it began.
     */
    @Transactional
    public boolean unpublish(Long actorUserId, Long boothId, String reason) {
        admins.requireAdmin(actorUserId);
        Booth booth = booths.findWithLockById(boothId)
                .orElseThrow(() -> new BoothNotFoundException(boothId));
        admins.requireOwnerNotMaster(booth.getOwnerUserId());
        if (!booth.isPublished()) {
            return false;
        }
        booth.clearPublishedLayoutVersion();
        actions.record(actorUserId, AdminActionRecorder.BOOTH_UNPUBLISH,
                AdminActionRecorder.TARGET_BOOTH, boothId, reason);
        return true;
    }
}
