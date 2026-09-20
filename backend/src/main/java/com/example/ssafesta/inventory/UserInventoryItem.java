package com.example.ssafesta.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Records one durable catalog-item ownership for a member. */
@Entity
@Table(name = "user_inventory_items")
public class UserInventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "catalog_item_id", nullable = false, updatable = false)
    private Long catalogItemId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "acquired_via", nullable = false, updatable = false, length = 30)
    private String acquiredVia;

    @Column(name = "acquired_at", nullable = false, updatable = false)
    private Instant acquiredAt;

    protected UserInventoryItem() {
    }

    public UserInventoryItem(Long userId, Long catalogItemId) {
        this.userId = userId;
        this.catalogItemId = catalogItemId;
        this.quantity = 1;
        this.acquiredVia = "PURCHASE";
        this.acquiredAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
}
