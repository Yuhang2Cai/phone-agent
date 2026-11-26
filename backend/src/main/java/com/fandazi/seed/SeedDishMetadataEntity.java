// 演示数据模块：定义持久化数据结构。
package com.fandazi.seed;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;

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
    @Column(nullable=false, length=2000) @Convert(converter = StringListJsonConverter.class) private List<String> ingredients;
    @Column(nullable=false, length=2000) @Convert(converter = StringListJsonConverter.class) private List<String> allergens;
// 初始化对象依赖与默认状态。
    protected SeedDishMetadataEntity() {}
// 初始化对象依赖与默认状态。
    public SeedDishMetadataEntity(Long dishId, String dishName, BigDecimal previousPrice, boolean priceChanged, BigDecimal rating, String spiciness, List<String> ingredients, List<String> allergens) { this.dishId=dishId; this.dishName=dishName; this.previousPrice=previousPrice; this.priceChanged=priceChanged; this.rating=rating; this.spiciness=spiciness; this.ingredients=List.copyOf(ingredients); this.allergens=List.copyOf(allergens); }
// 获取菜品、id。
    // 获取菜品、name。
    // 获取previous、价格。
    // 判断价格、changed。
    // 获取rating。
    // 获取spiciness。
    // 获取ingredients。
    // 获取allergens。
    public Long getDishId(){return dishId;} public String getDishName(){return dishName;} public BigDecimal getPreviousPrice(){return previousPrice;} public boolean isPriceChanged(){return priceChanged;} public BigDecimal getRating(){return rating;} public String getSpiciness(){return spiciness;} public List<String> getIngredients(){return ingredients;} public List<String> getAllergens(){return allergens;}
}
