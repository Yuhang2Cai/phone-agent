// 购物车模块：定义该模块的数据与行为。
package com.fandazi.feature.cart;
import com.fandazi.feature.auth.UserEntity; import com.fandazi.feature.catalog.MerchantEntity; import jakarta.persistence.*; import java.util.*;
// 初始化对象依赖与默认状态。
// 初始化对象依赖与默认状态。
// 获取id。
// 获取用户。
// 获取商家。
// 获取items。
// 处理 `replaceItems` 相关逻辑。
@Entity @Table(name="cart_drafts") public class CartDraft { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) private UserEntity user; @ManyToOne(optional=false) private MerchantEntity merchant; @OneToMany(mappedBy="draft",cascade=CascadeType.ALL,orphanRemoval=true) private List<CartDraftItem> items=new ArrayList<>(); protected CartDraft(){} public CartDraft(UserEntity user,MerchantEntity merchant){this.user=user;this.merchant=merchant;} public Long getId(){return id;} public UserEntity getUser(){return user;} public MerchantEntity getMerchant(){return merchant;} public List<CartDraftItem> getItems(){return items;} public void replaceItems(List<CartDraftItem> values){items.clear();items.addAll(values);} }
