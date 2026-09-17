package com.example.ssafesta.booth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the D07 lease-warning scan without making the scheduled job itself business authority. */
@Component
class BoothLeaseExpiryWarningScheduler {
    private static final Logger log = LoggerFactory.getLogger(BoothLeaseExpiryWarningScheduler.class);

    private final BoothLeaseExpiryWarningService warnings;

    BoothLeaseExpiryWarningScheduler(BoothLeaseExpiryWarningService warnings) {
        this.warnings = warnings;
    }

    @Scheduled(fixedDelayString = "${app.lease.expiry-warning-scan-interval:PT1M}")
    public void notifyExpiringLeases() {
        int notified = warnings.notifyExpiringLeases();
        if (notified > 0) {
            log.info("만료 임박 임대 {}건을 알렸습니다.", notified);
        }
    }
}
