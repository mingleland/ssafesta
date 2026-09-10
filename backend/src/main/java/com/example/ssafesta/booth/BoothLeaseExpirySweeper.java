package com.example.ssafesta.booth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves leases whose time has passed to {@code EXPIRED} and releases their slots
 * (S15P21A604-152, docs/08 "Lease 만료 처리 계약").
 *
 * <p><b>The read still decides validity.</b> spec 004 C-02 made {@code status = ACTIVE AND ends_at
 * > now} authoritative precisely so that a stopped scheduler cannot let an expired booth be entered
 * (SC-003), and {@code research.md} left the batch as something to add on top when needed. This is
 * that batch, and nothing here is load-bearing for correctness of entry or of the one-lease limit.
 *
 * <p>What it fixes is the gap the lazy paths cannot reach. {@link BoothLeaseService} transitions
 * stale rows only when someone re-leases that same slot or that same member leases again — so an
 * expired booth on a slot nobody wants keeps {@code current_slot_id}, and with it a published layout
 * that {@link Booth#detachSlot} is supposed to have withdrawn. The pass closes that without waiting
 * for a request that may never come.
 *
 * <p>Deliberately thin: the work is in {@link BoothLeaseService#expireStaleLeases()} so that its
 * transaction is entered through a proxy, and so that the sweeper and the request paths share one
 * expiry method rather than two that drift.
 */
@Component
class BoothLeaseExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(BoothLeaseExpirySweeper.class);

    private final BoothLeaseService leases;

    BoothLeaseExpirySweeper(BoothLeaseService leases) {
        this.leases = leases;
    }

    /**
     * Five minutes by default — nothing waits on this pass, and the lease term is 24 hours.
     *
     * <p>Configurable for the same reason {@code app.ai.document.expiry-scan-interval} is: the
     * cadence is an operational knob, while the 24-hour term itself is a contract
     * ({@link LeaseProperties}).
     */
    @Scheduled(fixedDelayString = "${app.lease.expiry-scan-interval:PT5M}")
    public void expireStaleLeases() {
        int expired = leases.expireStaleLeases();
        if (expired > 0) {
            // Not debug: this is the only trace that a booth left its slot without anyone asking.
            log.info("만료 임대 {}건을 정리했습니다.", expired);
        }
    }
}
