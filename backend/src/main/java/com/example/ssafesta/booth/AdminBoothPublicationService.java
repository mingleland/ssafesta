package com.example.ssafesta.booth;

import com.example.ssafesta.user.AdminActionRecorder;
import com.example.ssafesta.user.AdminGuard;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Performs the admin-only emergency action that removes a booth from visitor publication. */
@Service
public class AdminBoothPublicationService {

    private final BoothRepository booths;
    private final BoothLeaseRepository leases;
    private final BoothLeaseService leaseService;
    private final AdminGuard admins;
    private final AdminActionRecorder actions;

    public AdminBoothPublicationService(BoothRepository booths, BoothLeaseRepository leases,
                                        BoothLeaseService leaseService, AdminGuard admins,
                                        AdminActionRecorder actions) {
        this.booths = booths;
        this.leases = leases;
        this.leaseService = leaseService;
        this.admins = admins;
        this.actions = actions;
    }

    /**
     * Takes the booth offline <b>and takes its seat back</b> (S15P21A604-927).
     *
     * <p><b>Why the seat goes too.</b> Clearing the pointer alone left the lease alive, so
     * {@code GET /booth-slots} kept reporting the slot {@code OCCUPIED} and the console — which reads
     * that list — showed no change at all. An administrator pressing 강제 비공개 means "this booth is
     * off the floor", and half of that was not happening. There is no second endpoint for the other
     * half: the console calls this one, as it already did.
     *
     * <p>Versions and drafts are still evidence and remain intact, and so does everything else the
     * owner made — the release path preserves a member's booth exactly as spec 004 FR-010 requires
     * (an administrator's <i>own</i> booth is deleted by its release, which is that booth's own
     * rule, S15P21A604-905). No refund (D06).
     *
     * <p><b>The order is deliberate.</b> The release runs first because {@link Booth#detachSlot}
     * already clears the published pointer (spec 005 FR-017) — so for a leased booth the release
     * <i>is</i> the unpublish, and nothing has to clear it twice. It also keeps the lock order the
     * same as {@link BoothLeaseService#lease}'s (wallet, then booth); taking the booth lock first and
     * the wallet second is how the two paths would deadlock against each other. Only a booth with no
     * valid lease — already expired — falls back to the pointer-only path, and that one takes the
     * booth row lock so a stale emergency request cannot erase a version published after it began.
     *
     * @return whether this call actually had a published pointer to remove. A replay is a no-op and
     *         writes no second audit row, which is the existing contract
     */
    @Transactional
    public boolean unpublish(Long actorUserId, Long boothId, String reason) {
        admins.requireAdmin(actorUserId);
        Booth booth = booths.findById(boothId)
                .orElseThrow(() -> new BoothNotFoundException(boothId));
        // 같은 이유로 관리자 부스에는 마스터 보호를 적용하지 않는다 — BoothAccessGuard.requireModifier
        // 와 규칙이 갈리면 편집은 되는데 비공개는 안 되는 상태가 된다 (S15P21A604-905).
        if (!booth.isAdminOwned()) {
            admins.requireOwnerNotMaster(booth.getOwnerUserId());
        }

        boolean published = booth.isPublished();
        Optional<BoothLease> lease = leases.findValidByBoothId(boothId, Instant.now());
        if (lease.isPresent()) {
            // 자리 회수가 비공개를 포함한다 — detachSlot 이 공개 포인터를 비운다.
            leaseService.releaseByAdmin(actorUserId, lease.get().getSlotId(), reason);
        } else if (published) {
            booths.findWithLockById(boothId)
                    .orElseThrow(() -> new BoothNotFoundException(boothId))
                    .clearPublishedLayoutVersion();
        }

        if (!published) {
            return false;
        }
        actions.record(actorUserId, AdminActionRecorder.BOOTH_UNPUBLISH,
                AdminActionRecorder.TARGET_BOOTH, boothId, reason);
        return true;
    }
}
