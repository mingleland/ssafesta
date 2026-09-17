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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EventPrize() {
    }

    public EventPrize(String name, int priceCoin, Integer stock) {
        this.name = name;
        this.priceCoin = priceCoin;
        this.stock = stock;
    }

    public void update(String name, int priceCoin, Integer stock, boolean active) {
        this.name = name;
        this.priceCoin = priceCoin;
        this.stock = stock;
        this.active = active;
        this.updatedAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
