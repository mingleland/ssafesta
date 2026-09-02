package com.example.ssafesta.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the Spring-owned sale catalog that clients use to render item availability. */
@Entity
@Table(name = "catalog_items")
public class CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_code", nullable = false, unique = true, length = 50)
    private String itemCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "item_type", nullable = false, length = 30)
    private String itemType;

    @Column(name = "equip_slot", length = 30)
    private String equipSlot;

    @Column(nullable = false)
    private int price;

    @Column(name = "asset_key", nullable = false, length = 255)
    private String assetKey;

    @Column(name = "is_stackable", nullable = false)
    private boolean stackable;

    @Column(name = "is_on_sale", nullable = false)
    private boolean onSale;

    protected CatalogItem() {
    }

    public Long getId() { return id; }
    public String getItemCode() { return itemCode; }
    public String getName() { return name; }
    public String getItemType() { return itemType; }
    public String getEquipSlot() { return equipSlot; }
    public int getPrice() { return price; }
    public String getAssetKey() { return assetKey; }
    public boolean isStackable() { return stackable; }
    public boolean isOnSale() { return onSale; }
}
