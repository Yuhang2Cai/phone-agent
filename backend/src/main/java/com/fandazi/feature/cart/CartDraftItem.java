// 购物车模块：定义该模块的数据与行为。
package com.fandazi.feature.cart;
import com.fandazi.feature.catalog.DishEntity; import jakarta.persistence.*; import java.util.*;
// 初始化对象依赖与默认状态。
// 初始化对象依赖与默认状态。
// 获取菜品。
// 获取quantity。
// 获取option、ids。
@Entity @Table(name="cart_draft_items") public class CartDraftItem { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) private CartDraft draft; @ManyToOne(optional=false) private DishEntity dish; @Column(nullable=false) private int quantity; @ElementCollection @CollectionTable(name="cart_draft_item_options",joinColumns=@JoinColumn(name="item_id")) @Column(name="option_id",nullable=false) private List<Long> optionIds=new ArrayList<>(); protected CartDraftItem(){} CartDraftItem(CartDraft draft,DishEntity dish,int quantity,List<Long> optionIds){this.draft=draft;this.dish=dish;this.quantity=quantity;this.optionIds=new ArrayList<>(optionIds==null?List.of():optionIds);} public DishEntity getDish(){return dish;} public int getQuantity(){return quantity;} public List<Long> getOptionIds(){return List.copyOf(optionIds);} }
