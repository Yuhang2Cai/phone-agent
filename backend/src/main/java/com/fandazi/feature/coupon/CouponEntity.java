// 优惠券模块：保存团购平台可发放的优惠券定义。
package com.fandazi.feature.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** 优惠券规则定义，金额单位为分。 */
@Entity
@Table(name = "coupons")
public class CouponEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @Column(nullable = false) private String name;
    // Java 保留整数分接口，数据库权威列使用 DECIMAL 元，显式转换防止误改其他整数。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "threshold_amount", nullable = false, precision = 12, scale = 2) private int thresholdCents;
    // Java 保留整数分接口，数据库权威列使用 DECIMAL 元，显式转换防止误改其他整数。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2) private int discountCents;
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to", nullable = false) private LocalDate validTo;
    @Column(name = "scope_description", nullable = false, length = 500) private String scopeDescription;
    @Column(nullable = false) private boolean active;

    protected CouponEntity() {}

    /** 创建一个固定门槛和优惠金额的优惠券定义。 */
    public CouponEntity(String publicId, String name, int thresholdCents, int discountCents,
                        LocalDate validFrom, LocalDate validTo, String scopeDescription, boolean active) {
        this.publicId = publicId; this.name = name; this.thresholdCents = thresholdCents;
        this.discountCents = discountCents; this.validFrom = validFrom; this.validTo = validTo;
        this.scopeDescription = scopeDescription; this.active = active;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getName() { return name; }
    public int getThresholdCents() { return thresholdCents; }
    public int getDiscountCents() { return discountCents; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getScopeDescription() { return scopeDescription; }
    public boolean isActive() { return active; }
}
