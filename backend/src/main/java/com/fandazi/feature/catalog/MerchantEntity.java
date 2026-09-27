// 商家与菜品目录模块：定义持久化数据结构。
package com.fandazi.feature.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "merchants")
public class MerchantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String category;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal minimumOrder;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal deliveryFee;
    @Column(nullable = false) private int deliveryMinutes;
    @Column(nullable = false) private boolean open;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal discountThreshold;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal discountAmount;
    protected MerchantEntity() {}
    public MerchantEntity(String name, String category, BigDecimal minimumOrder, BigDecimal deliveryFee, int deliveryMinutes, boolean open) {
        this(name, category, minimumOrder, deliveryFee, deliveryMinutes, open, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    public MerchantEntity(String name, String category, BigDecimal minimumOrder, BigDecimal deliveryFee, int deliveryMinutes,
                          boolean open, BigDecimal discountThreshold, BigDecimal discountAmount) {
        this.name=name; this.category=category; this.minimumOrder=minimumOrder; this.deliveryFee=deliveryFee;
        this.deliveryMinutes=deliveryMinutes; this.open=open; this.discountThreshold=discountThreshold; this.discountAmount=discountAmount;
    }
    public Long getId(){return id;} public String getName(){return name;} public String getCategory(){return category;} public BigDecimal getMinimumOrder(){return minimumOrder;} public BigDecimal getDeliveryFee(){return deliveryFee;} public int getDeliveryMinutes(){return deliveryMinutes;} public boolean isOpen(){return open;} public BigDecimal getDiscountThreshold(){return discountThreshold;} public BigDecimal getDiscountAmount(){return discountAmount;}
}
