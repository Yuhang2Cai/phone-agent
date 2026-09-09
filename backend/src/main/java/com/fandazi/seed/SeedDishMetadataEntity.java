package com.fandazi.seed;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "seed_dish_metadata")
public class SeedDishMetadataEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long dishId;
    @Column(nullable=false) private String dishName;
    @Column(precision=12, scale=2) private BigDecimal previousPrice;
    @Column(nullable=false) private boolean priceChanged;
    @Column(nullable=false, precision=3, scale=1) private BigDecimal rating;
    @Column(nullable=false) private String spiciness;
    @Column(nullable=false, length=2000) private String ingredients;
    @Column(nullable=false, length=2000) private String allergens;
    protected SeedDishMetadataEntity() {}
    public SeedDishMetadataEntity(Long dishId, String dishName, BigDecimal previousPrice, boolean priceChanged, BigDecimal rating, String spiciness, String ingredients, String allergens) { this.dishId=dishId; this.dishName=dishName; this.previousPrice=previousPrice; this.priceChanged=priceChanged; this.rating=rating; this.spiciness=spiciness; this.ingredients=ingredients; this.allergens=allergens; }
}
