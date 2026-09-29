// 商家与菜品目录模块：定义旧外卖商家和团购品牌之间的兼容持久化结构。
package com.fandazi.feature.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 旧商家实体。
 *
 * <p>团购门店通过可选的 merchant 关联复用已有品牌，但跨服务只暴露 publicId。
 * publicId 根据品牌名称生成稳定值，避免重新装载种子时发生资源标识漂移。</p>
 */
@Entity
@Table(name = "merchants")
public class MerchantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", unique = true, length = 80) private String publicId;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String category;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal minimumOrder;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal deliveryFee;
    @Column(nullable = false) private int deliveryMinutes;
    @Column(nullable = false) private boolean open;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal discountThreshold;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal discountAmount;

    protected MerchantEntity() {}

    public MerchantEntity(String name, String category, BigDecimal minimumOrder, BigDecimal deliveryFee,
                          int deliveryMinutes, boolean open) {
        this(name, category, minimumOrder, deliveryFee, deliveryMinutes, open, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public MerchantEntity(String name, String category, BigDecimal minimumOrder, BigDecimal deliveryFee,
                          int deliveryMinutes, boolean open, BigDecimal discountThreshold, BigDecimal discountAmount) {
        this.publicId = stablePublicId(name);
        this.name = name;
        this.category = category;
        this.minimumOrder = minimumOrder;
        this.deliveryFee = deliveryFee;
        this.deliveryMinutes = deliveryMinutes;
        this.open = open;
        this.discountThreshold = discountThreshold;
        this.discountAmount = discountAmount;
    }

    /** 将品牌名称映射为稳定的公开标识，保证重复 seed 不产生新的品牌地址。 */
    private static String stablePublicId(String name) {
        return "merchant-" + UUID.nameUUIDFromBytes(name.trim().getBytes(StandardCharsets.UTF_8));
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public BigDecimal getMinimumOrder() { return minimumOrder; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public int getDeliveryMinutes() { return deliveryMinutes; }
    public boolean isOpen() { return open; }
    public BigDecimal getDiscountThreshold() { return discountThreshold; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
}
