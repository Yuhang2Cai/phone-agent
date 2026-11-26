// 团购目录模块：定义品牌门店的持久化结构和公开查询字段。
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
import java.math.BigDecimal;

/**
 * 到店团购门店。
 *
 * <p>门店与旧外卖商家分离，门店保存位置、人均和营业信息；merchant 只作为
 * 兼容已有品牌数据的可选关联，Agent 对外只使用 publicId。</p>
 */
@Entity
@Table(name = "stores")
public class StoreEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @Column(name = "brand_name", nullable = false) private String brandName;
    @Column(nullable = false) private String name;
    @Column(name = "city_code", nullable = false, length = 32) private String cityCode;
    @Column(nullable = false) private String address;
    @Column(nullable = false, length = 64) private String phone;
    @Column(nullable = false, length = 64) private String category;
    @Column(nullable = false, precision = 10, scale = 7) private BigDecimal latitude;
    @Column(nullable = false, precision = 10, scale = 7) private BigDecimal longitude;
    // Java 保留整数分接口，数据库权威列使用 DECIMAL 元，显式转换防止误改其他整数。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "average_spend_amount", nullable = false, precision = 12, scale = 2) private int averageSpendCents;
    @Column(nullable = false) private boolean open;
    @Column(name = "opening_hours", nullable = false, length = 128) private String openingHours;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "merchant_id") private MerchantEntity merchant;

    protected StoreEntity() {}

    /** 创建一个可供推荐和排序使用的门店。 */
    public StoreEntity(String publicId, String brandName, String name, String cityCode, String address, String phone,
                       String category, BigDecimal latitude, BigDecimal longitude, int averageSpendCents,
                       boolean open, String openingHours, MerchantEntity merchant) {
        this.publicId = publicId; this.brandName = brandName; this.name = name; this.cityCode = cityCode;
        this.address = address; this.phone = phone; this.category = category; this.latitude = latitude;
        this.longitude = longitude; this.averageSpendCents = averageSpendCents; this.open = open;
        this.openingHours = openingHours; this.merchant = merchant;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getBrandName() { return brandName; }
    public String getName() { return name; }
    public String getCityCode() { return cityCode; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getCategory() { return category; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public int getAverageSpendCents() { return averageSpendCents; }
    public boolean isOpen() { return open; }
    public String getOpeningHours() { return openingHours; }
    public MerchantEntity getMerchant() { return merchant; }
}
