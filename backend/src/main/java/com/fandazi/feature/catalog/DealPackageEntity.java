// 团购目录模块：定义固定套餐的价格、库存和有效期。
package com.fandazi.feature.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** 固定团购套餐；首版不支持菜品规格和购物车组合。 */
@Entity
@Table(name = "deal_packages")
public class DealPackageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "store_id", nullable = false) private StoreEntity store;
    @Column(nullable = false) private String title;
    @Column(nullable = false, length = 2000) private String description;
    @Column(name = "price_cents", nullable = false) private int priceCents;
    @Column(name = "original_price_cents", nullable = false) private int originalPriceCents;
    @Column(nullable = false) private int inventory;
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to", nullable = false) private LocalDate validTo;
    @Column(nullable = false) private boolean active;
    @Column(name = "purchase_limit", nullable = false) private int purchaseLimit;
    @Column(nullable = false) private int version;

    protected DealPackageEntity() {}

    /** 创建一个固定价格的团购套餐。 */
    public DealPackageEntity(String publicId, StoreEntity store, String title, String description, int priceCents,
                             int originalPriceCents, int inventory, LocalDate validFrom, LocalDate validTo,
                             boolean active, int purchaseLimit, int version) {
        this.publicId = publicId; this.store = store; this.title = title; this.description = description;
        this.priceCents = priceCents; this.originalPriceCents = originalPriceCents; this.inventory = inventory;
        this.validFrom = validFrom; this.validTo = validTo; this.active = active;
        this.purchaseLimit = purchaseLimit; this.version = version;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public StoreEntity getStore() { return store; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getPriceCents() { return priceCents; }
    public int getOriginalPriceCents() { return originalPriceCents; }
    public int getInventory() { return inventory; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public boolean isActive() { return active; }
    public int getPurchaseLimit() { return purchaseLimit; }
    public int getVersion() { return version; }
}
