package com.fandazi.feature.catalog;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="dish_options")
public class DishOptionEntity { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) private DishEntity dish; @Column(nullable=false) private String groupName; @Column(nullable=false) private String optionName; @Column(nullable=false, precision=12, scale=2) private BigDecimal priceDelta; protected DishOptionEntity(){} public DishOptionEntity(DishEntity dish,String groupName,String optionName,BigDecimal priceDelta){this.dish=dish;this.groupName=groupName;this.optionName=optionName;this.priceDelta=priceDelta;} public Long getId(){return id;} public DishEntity getDish(){return dish;} public String getGroupName(){return groupName;} public String getOptionName(){return optionName;} public BigDecimal getPriceDelta(){return priceDelta;} }
