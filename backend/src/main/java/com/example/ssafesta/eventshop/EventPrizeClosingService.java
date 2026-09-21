package com.example.ssafesta.eventshop;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes prizes at their deadline and draws the winners of raffles (S15P21A604-922).
 *
 * <p>Two separate passes rather than one: a prize can reach the draw without ever passing its
 * deadline (an administrator took it off sale by hand), and a prize can close without being a
 * raffle at all. Keeping them apart means either can happen on its own.
 *
 * <p>A sweep is one transaction. Each prize is still taken under its own row lock, so a draw never
 * runs beside a purchase of the same prize — the lock is what matters here, and per-prize
 * transactions would not survive self-invocation anyway (Spring's proxy only advises calls that
 * arrive from outside the bean).
 */
@Service
public class EventPrizeClosingService {

    private final EventPrizeRepository prizes;
    private final EventPurchaseRepository purchases;
    private final Random random;

    public EventPrizeClosingService(EventPrizeRepository prizes, EventPurchaseRepository purchases) {
        this.prizes = prizes;
        this.purchases = purchases;
        this.random = new SecureRandom();
    }

    /** @return how many prizes went off sale */
    @Transactional
    public int closeExpiredPrizes(Instant now) {
        int closed = 0;
        for (Long prizeId : prizes.findIdsToClose(now)) {
            closed += closeOne(prizeId, now) ? 1 : 0;
        }
        return closed;
    }

    /** @return how many raffles were drawn */
    @Transactional
    public int drawPendingRaffles(Instant now) {
        int drawn = 0;
        for (Long prizeId : prizes.findIdsToDraw()) {
            drawn += drawOne(prizeId, now) ? 1 : 0;
        }
        return drawn;
    }

    private boolean closeOne(Long prizeId, Instant now) {
        EventPrize prize = prizes.findByIdForUpdate(prizeId).orElse(null);
        if (prize == null || !prize.isActive() || !prize.isClosedAt(now)) {
            return false;
        }
        prize.close(now);
        return true;
    }

    /**
     * Picks {@code winnerCount} entries at random and marks everyone else as a loser.
     *
     * <p>{@code drawnAt} is written in the same transaction as the verdicts, which is what makes a
     * second pass a no-op: the query that feeds this method only returns prizes where it is null.
     * A raffle nobody entered is still marked drawn — leaving it unmarked would make the sweeper
     * retry it forever.
     */
    private boolean drawOne(Long prizeId, Instant now) {
        EventPrize prize = prizes.findByIdForUpdate(prizeId).orElse(null);
        if (prize == null || !prize.isRaffle() || prize.getDrawnAt() != null) {
            return false;
        }
        List<EventPurchase> entries = new ArrayList<>(purchases.findAllByPrizeIdOrderByIdAsc(prizeId));
        Collections.shuffle(entries, random);
        int winners = Math.min(prize.getWinnerCount(), entries.size());
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).recordDraw(i < winners, now);
        }
        prize.markDrawn(now);
        return true;
    }
}
