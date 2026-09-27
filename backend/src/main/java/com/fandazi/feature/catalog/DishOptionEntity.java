// 商家与菜品目录模块：定义持久化数据结构。
package com.fandazi.feature.catalog;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="dish_options")
// 初始化对象依赖与默认状态。
// 初始化对象依赖与默认状态。
// 初始化对象依赖与默认状态。
// 获取id。
// 获取菜品。
// 获取group、name。
// 获取option、name。
// 获取价格、delta。
// 判断available。
public class DishOptionEntity { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) private DishEntity dish; @Column(nullable=false) private String groupName; @Column(nullable=false) private String optionName; @Column(nullable=false, precision=12, scale=2) private BigDecimal priceDelta; @Column(nullable=false) private boolean available; protected DishOptionEntity(){} public DishOptionEntity(DishEntity dish,String groupName,String optionName,BigDecimal priceDelta){this(dish,groupName,optionName,priceDelta,true);} public DishOptionEntity(DishEntity dish,String groupName,String optionName,BigDecimal priceDelta,boolean available){this.dish=dish;this.groupName=groupName;this.optionName=optionName;this.priceDelta=priceDelta;this.available=available;} public Long getId(){return id;} public DishEntity getDish(){return dish;} public String getGroupName(){return groupName;} public String getOptionName(){return optionName;} public BigDecimal getPriceDelta(){return priceDelta;} public boolean isAvailable(){return available;} }
