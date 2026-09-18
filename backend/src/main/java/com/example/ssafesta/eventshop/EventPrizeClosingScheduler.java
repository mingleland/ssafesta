package com.example.ssafesta.eventshop;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link EventPrizeClosingService} on a fixed cadence (S15P21A604-922), the same shape
 * {@code BoothLeaseExpirySweeper} uses.
 *
 * <p>The interval is an operational knob; the deadline itself is the prize's {@code closesAt}, and
 * {@code EventShopService.purchase} refuses late entries regardless of when this last ran.
 */
@Component
public class EventPrizeClosingScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventPrizeClosingScheduler.class);

    private final EventPrizeClosingService closing;

    public EventPrizeClosingScheduler(EventPrizeClosingService closing) {
        this.closing = closing;
    }

    @Scheduled(fixedDelayString = "${app.event-shop.close-scan-interval:PT1M}")
    public void closeAndDraw() {
        Instant now = Instant.now();
        int closed = closing.closeExpiredPrizes(now);
        if (closed > 0) {
            log.info("마감된 경품 {}건의 판매를 내렸습니다.", closed);
        }
        int drawn = closing.drawPendingRaffles(now);
        if (drawn > 0) {
            // Not debug: this is the only trace that a winner was picked, and it happens once.
            log.info("응모형 경품 {}건의 추첨을 마쳤습니다.", drawn);
        }
    }
}
