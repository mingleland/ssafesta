package com.example.ssafesta.eventshop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A prize administrators can register for the event shop (S15P21A604-836).
 *
 * <p>There is no delete API — a prize that has ever been purchased is referenced by
 * {@link EventPurchase} rows, and removing the row a purchase points at would make the purchase
 * history unreadable. Taking it off sale is {@link #active} {@code false}, the same shape
 * {@code CatalogItem.onSale} uses.
 */
@Entity
@Table(name = "event_prizes")
public class EventPrize {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "price_coin", nullable = false)
    private int priceCoin;

    /** {@code null} means unlimited — nothing to decrement, {@link #reserve} always succeeds. */
    @Column
    private Integer stock;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * When this prize stops being sold. {@code null} means it never closes on its own.
     *
     * <p>The scheduler flips {@link #active} once this passes, but the scheduler is not the
     * boundary — {@code EventShopService.purchase} checks this directly, so a purchase arriving
     * between the deadline and the next sweep is still refused.
     */
    @Column(name = "closes_at")
    private Instant closesAt;

    /** How many entrants win. {@code 0} means this is an ordinary prize — nothing is drawn. */
    @Column(name = "winner_count", nullable = false)
    private int winnerCount;

    /** When the draw actually ran. The only thing that stops a second draw. */
    @Column(name = "drawn_at")
    private Instant drawnAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EventPrize() {
    }

    /** An ordinary prize: bought outright, no deadline, nothing drawn. */
    public EventPrize(String name, int priceCoin, Integer stock) {
        this(name, priceCoin, stock, null, 0);
    }

    public EventPrize(String name, int priceCoin, Integer stock, Instant closesAt, int winnerCount) {
        this.name = name;
        this.priceCoin = priceCoin;
        this.stock = stock;
        this.closesAt = closesAt;
        this.winnerCount = winnerCount;
    }

    public void update(String name, int priceCoin, Integer stock, boolean active, Instant closesAt,
                       int winnerCount) {
        this.name = name;
        this.priceCoin = priceCoin;
        this.stock = stock;
        this.active = active;
        this.closesAt = closesAt;
        this.winnerCount = winnerCount;
        this.updatedAt = Instant.now();
    }

    /**
     * A prize entrants enter rather than buy outright: coins buy a chance, and the winners are
     * picked after it closes.
     */
    public boolean isRaffle() {
        return winnerCount > 0;
    }

    /** Whether {@code now} is past the deadline. A prize with no deadline never closes. */
    public boolean isClosedAt(Instant now) {
        return closesAt != null && !now.isBefore(closesAt);
    }

    /** Takes the prize off sale. Idempotent — closing an already-closed prize changes nothing. */
    public void close(Instant now) {
        if (active) {
            this.active = false;
            this.updatedAt = now;
        }
    }

    public void markDrawn(Instant now) {
        this.drawnAt = now;
        this.updatedAt = now;
    }

    /**
     * Takes {@code quantity} units off the shelf.
     *
     * <p>Call this only while holding the prize's row lock ({@code EventPrizeRepository
     * .findByIdForUpdate}) — a check-then-act without it lets two concurrent purchases both see
     * enough stock and both succeed, oversubscribing the prize.
     *
     * @return {@code false} when stock is tracked and insufficient; the caller must not spend coins
     *         or record a purchase when this returns {@code false}
     */
    public boolean reserve(int quantity) {
        if (stock == null) {
            return true;
        }
        if (stock < quantity) {
            return false;
        }
        stock -= quantity;
        updatedAt = Instant.now();
        return true;
    }

    /**
     * Puts {@code quantity} units back on the shelf — the exact counterpart of {@link #reserve},
     * called when a purchase is cancelled (S15P21A604-922 후속).
     *
     * <p>Untracked stock ({@code null}) stays untracked: it was never decremented, so restoring it
     * would invent inventory. Takes the same row lock as {@link #reserve} for the same reason.
     */
    public void restore(int quantity) {
        if (stock == null) {
            return;
        }
        stock += quantity;
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPriceCoin() {
        return priceCoin;
    }

    public Integer getStock() {
        return stock;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public int getWinnerCount() {
        return winnerCount;
    }

    public Instant getDrawnAt() {
        return drawnAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
