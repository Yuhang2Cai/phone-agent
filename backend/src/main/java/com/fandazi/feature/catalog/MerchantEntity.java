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
    protected MerchantEntity() {}
    public MerchantEntity(String name, String category, BigDecimal minimumOrder, BigDecimal deliveryFee, int deliveryMinutes, boolean open) { this.name=name; this.category=category; this.minimumOrder=minimumOrder; this.deliveryFee=deliveryFee; this.deliveryMinutes=deliveryMinutes; this.open=open; }
    public Long getId(){return id;} public String getName(){return name;} public String getCategory(){return category;} public BigDecimal getMinimumOrder(){return minimumOrder;} public BigDecimal getDeliveryFee(){return deliveryFee;} public int getDeliveryMinutes(){return deliveryMinutes;} public boolean isOpen(){return open;}
}
