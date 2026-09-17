package com.example.ssafesta.booth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds each tenant's one-hour lease warning once and hands it to the private WebSocket channel. */
@Service
public class BoothLeaseExpiryWarningService {
    private static final Duration WARNING_LEAD = Duration.ofHours(1);
    /** There are currently only 11 rentable slots; this remains a bounded transaction if that grows. */
    private static final int WARNING_BATCH = 20;

    private final BoothLeaseRepository leases;
    private final BoothLeaseExpiryWarningPublisher publisher;

    BoothLeaseExpiryWarningService(BoothLeaseRepository leases, BoothLeaseExpiryWarningPublisher publisher) {
        this.leases = leases;
        this.publisher = publisher;
    }

    /**
     * Claims all currently unwarned active leases with one hour or less left.
     *
     * <p>The warning can arrive later than one hour when the service was unavailable, but never
     * after expiry and never more than once for the same lease. On reconnect the REST lease view is
     * still authoritative; STOMP deliberately has no replay contract.
     */
    @Transactional
    public int notifyExpiringLeases() {
        Instant now = Instant.now();
        List<BoothLease> candidates = leases.findUnwarnedExpiring(now, now.plus(WARNING_LEAD), WARNING_BATCH);
        for (BoothLease lease : candidates) {
            lease.markExpiryWarningSent(now);
            publisher.expiring(lease.getLesseeUserId(), lease.getId(), lease.getBoothId(), lease.getEndsAt(),
                    lease.remainingSecondsAt(now));
        }
        return candidates.size();
    }
}
