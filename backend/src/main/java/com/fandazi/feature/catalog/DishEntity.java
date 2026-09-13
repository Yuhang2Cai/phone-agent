package com.fandazi.feature.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "dishes")
public class DishEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private MerchantEntity merchant;
    @Column(nullable = false) private String category;
    @Column(nullable = false) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal price;
    @Column(nullable = false) private int stock;
    @Column(nullable = false) private boolean listed;
    @Column(nullable = false) private String imageUrl;
    protected DishEntity() {}
    public DishEntity(MerchantEntity merchant, String category, String name, String description, BigDecimal price, int stock, boolean listed, String imageUrl) { this.merchant=merchant; this.category=category; this.name=name; this.description=description; this.price=price; this.stock=stock; this.listed=listed; this.imageUrl=imageUrl; }
    public Long getId(){return id;} public MerchantEntity getMerchant(){return merchant;} public String getCategory(){return category;} public String getName(){return name;} public String getDescription(){return description;} public BigDecimal getPrice(){return price;} public int getStock(){return stock;} public boolean isListed(){return listed;} public String getImageUrl(){return imageUrl;} public void replaceImageUrl(String imageUrl){this.imageUrl=imageUrl;} public void reserve(int quantity){if(quantity>stock)throw new IllegalStateException("库存不足");stock-=quantity;}
}
