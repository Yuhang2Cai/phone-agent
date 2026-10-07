// 团购目录模块：定义固定套餐的价格、库存和有效期。
package com.fandazi.feature.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** 固定团购套餐；首版不支持菜品规格和购物车组合。 */
@Entity
@Table(name = "deal_packages")
public class DealPackageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "store_id", nullable = false) private StoreEntity store;
    @Column(nullable = false) private String title;
    @Column(nullable = false, length = 2000) private String description;
    // Java 保留整数分接口，数据库权威列使用 DECIMAL 元，显式转换防止误改其他整数。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "price_amount", nullable = false, precision = 12, scale = 2) private int priceCents;
    // Java 保留整数分接口，数据库权威列使用 DECIMAL 元，显式转换防止误改其他整数。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "original_price_amount", nullable = false, precision = 12, scale = 2) private int originalPriceCents;
    @Column(nullable = false) private int inventory;
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to", nullable = false) private LocalDate validTo;
    @Column(nullable = false) private boolean active;
    @Column(name = "purchase_limit", nullable = false) private int purchaseLimit;
    @Column(nullable = false) private int version;
    // 图片只来自该套餐自身审核过的资源；没有照片时保留 null，不能使用通用餐图假装真实套餐。
    @Column(name = "image_url", length = 1000) private String imageUrl;
    @Column(name = "image_alt", length = 255) private String imageAlt;

    // 三项均允许未知，迁移不回填猜测事实；人数只由运营权威数据明确提供。
    @Column(name="people_count") private Integer peopleCount;
    @jakarta.persistence.Convert(converter=DealContentsConverter.class)
    @Column(name="contents_json",columnDefinition="TEXT") private java.util.List<DealContent> contents;
    @Column(name="usage_rules",length=2000) private String usageRules;

    protected DealPackageEntity() {}

    /** 创建一个固定价格的团购套餐。 */
    public DealPackageEntity(String publicId, StoreEntity store, String title, String description, int priceCents,
                             int originalPriceCents, int inventory, LocalDate validFrom, LocalDate validTo,
                             boolean active, int purchaseLimit, int version) {
        this.publicId = publicId; this.store = store; this.title = title; this.description = description;
        this.priceCents = priceCents; this.originalPriceCents = originalPriceCents; this.inventory = inventory;
        this.validFrom = validFrom; this.validTo = validTo; this.active = active;
        this.purchaseLimit = purchaseLimit; this.version = version;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public StoreEntity getStore() { return store; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getPriceCents() { return priceCents; }
    public int getOriginalPriceCents() { return originalPriceCents; }
    public int getInventory() { return inventory; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public boolean isActive() { return active; }
    public int getPurchaseLimit() { return purchaseLimit; }
    public int getVersion() { return version; }

    /** 返回明确提供的人数；未知为null，不能按套餐标题推算。 */
    public Integer getPeopleCount(){return peopleCount;}
    /** 返回不可变公开内容快照；未提供为null，不触发任何远程或业务写入。 */
    public java.util.List<DealContent> getContents(){return contents==null?null:java.util.List.copyOf(contents);}
    /** 返回运营使用规则，未提供为null。 */
    public String getUsageRules(){return usageRules;}
    /** 原子校验三项公开资料后替换；非法人数/规模/规则抛参数异常，价格库存与版本保持。 */
    public void replaceCompareFacts(Integer peopleCount,java.util.List<DealContent> contents,String usageRules){
        // 输入先完整校验，不允许第一项写入后第二项失败造成半更新。
        if(peopleCount!=null && (peopleCount<1 || peopleCount>10000))throw new IllegalArgumentException("套餐人数无效");
        if(contents!=null && (contents.size()>100 || contents.stream().anyMatch(java.util.Objects::isNull)))throw new IllegalArgumentException("套餐内容无效");
        if(usageRules!=null && usageRules.length()>2000)throw new IllegalArgumentException("套餐规则过长");
        // 空白规则规范化为未提供；保留真实数量与原始内容，不解析标题/描述。
        this.peopleCount=peopleCount;this.contents=contents==null?null:java.util.List.copyOf(contents);
        this.usageRules=usageRules==null || usageRules.isBlank()?null:usageRules;
    }

    /** 返回该套餐真实图片 URL，未提供图片返回 null；不执行网络请求。 */
    public String getImageUrl() { return imageUrl; }
    /** 返回图片内容的替代文本，缺图或未标注时返回 null。 */
    public String getImageAlt() { return imageAlt; }

    /** 装载明确属于本套餐的图片；两个输入都允许 null，空白 URL 规范化为缺图。 */
    public void replaceImage(String imageUrl, String imageAlt) {
        // 审核餐图优先使用项目托管的版本化根路径；旧历史 HTTP(S) URL 仍可读取。
        // 根路径必须匹配固定资源形状，拒绝路径跳转、查询串和任意站内文件引用。
        if (imageUrl != null && !imageUrl.isBlank()
                && !imageUrl.matches("^/catalog-images/v[0-9]+/full/[a-z0-9-]+-[a-f0-9]{12}\\.jpg$")) {
            var uri = java.net.URI.create(imageUrl);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException("套餐图片地址必须为审核餐图路径或 HTTP(S) URL");
        }
        this.imageUrl = imageUrl == null || imageUrl.isBlank() ? null : imageUrl;
        this.imageAlt = this.imageUrl == null || imageAlt == null || imageAlt.isBlank() ? null : imageAlt;
    }
}
