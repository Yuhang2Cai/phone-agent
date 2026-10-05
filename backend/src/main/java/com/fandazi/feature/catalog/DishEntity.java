// 商家与菜品目录模块：定义持久化数据结构。
package com.fandazi.feature.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "dishes")
public class DishEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    // UUID独立于内部自增主键，只用于公开只读菜单的稳定身份；价格或名称变化不会重建。
    @Column(name = "public_id", nullable = false, unique = true, length = 36) private String publicId;
    @ManyToOne(optional = false) private MerchantEntity merchant;
    @Column(nullable = false) private String category;
    @Column(nullable = false) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal price;
    @Column(nullable = false) private int stock;
    @Column(nullable = false) private boolean listed;
    @Column(nullable = false) private String imageUrl;
    /** JPA反射专用构造；历史公开UUID由追加迁移回填，禁止每次加载重新生成。 */
    protected DishEntity() {}

    /** 创建商家菜品并一次生成公开身份；参数沿用目录字段，不执行库存预留或数据库写入。 */
    public DishEntity(MerchantEntity merchant, String category, String name, String description,
                      BigDecimal price, int stock, boolean listed, String imageUrl) {
        this.publicId = java.util.UUID.randomUUID().toString();
        this.merchant = merchant;
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.listed = listed;
        this.imageUrl = imageUrl;
    }

    /** 返回稳定公开菜单UUID，名称、图片、价格变化均不改变身份。 */
    public String getPublicId() { return publicId; }
    /** 内部持久化主键，公开浏览接口使用publicId。 */
    public Long getId() { return id; }
    /** 返回所属商家实体，用于现有门店菜单关联读取。 */
    public MerchantEntity getMerchant() { return merchant; }
    /** 返回服务器菜品分类，无本地推断或排序副作用。 */
    public String getCategory() { return category; }
    /** 返回持久化菜品名称，同名菜仍有各自公开UUID。 */
    public String getName() { return name; }
    /** 返回公开菜品说明。 */
    public String getDescription() { return description; }
    /** 返回数据库小数金额，接口投影统一转换为货币分。 */
    public BigDecimal getPrice() { return price; }
    /** 返回现有库存事实，不在列表绑定中预留。 */
    public int getStock() { return stock; }
    /** 返回现有上架状态，浏览重构不改变销售规则。 */
    public boolean isListed() { return listed; }
    /** 返回已审核图片地址，公开ID独立于图片版本。 */
    public String getImageUrl() { return imageUrl; }
    /** 替换图片元数据；调用方负责现有事务与来源审核，身份保持不变。 */
    public void replaceImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    /** 保留原库存预留行为；数量超过当前库存时抛出异常，公开列表不会调用此方法。 */
    public void reserve(int quantity) {
        if (quantity > stock) throw new IllegalStateException("库存不足");
        stock -= quantity;
    }
}
