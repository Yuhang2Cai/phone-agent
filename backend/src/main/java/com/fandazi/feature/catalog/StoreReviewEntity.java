// 团购评价模块：保存门店维度的评价，和旧外卖商家评价表隔离。
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
import java.time.Instant;

/** 到店团购门店评价。 */
@Entity
@Table(name = "store_reviews")
public class StoreReviewEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    // 评价身份和作者/内容无关，重复文本也须保持两个独立资源；旧记录由前向迁移补齐。
    @Column(name = "public_id", nullable = false, unique = true, length = 36) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "store_id", nullable = false) private StoreEntity store;
    @Column(name = "order_id", unique = true) private Long orderId;
    @Column(name = "reviewer_name", nullable = false, length = 80) private String reviewerName;
    @Column(nullable = false, precision = 2, scale = 1) private BigDecimal rating;
    @Column(nullable = false, length = 1000) private String content;
    @Column(nullable = false) private Instant createdAt;

    /** JPA反射构造；已有记录读取持久UUID，不在加载阶段生成新身份。 */
    protected StoreReviewEntity() {}

    /** 创建一条门店评价，评分范围由种子加载器和后续服务共同校验。 */
    public StoreReviewEntity(StoreEntity store, String reviewerName, BigDecimal rating, String content, Instant createdAt) {
        this.publicId = java.util.UUID.randomUUID().toString();
        this.store = store; this.reviewerName = reviewerName; this.rating = rating; this.content = content; this.createdAt = createdAt;
    }

    /** 用户评价额外绑定团购订单；历史种子评价不关联订单，仍保持原构造方式。 */
    public StoreReviewEntity(StoreEntity store, Long orderId, String reviewerName, BigDecimal rating, String content, Instant createdAt) {
        this(store, reviewerName, rating, content, createdAt);
        this.orderId = orderId;
    }

    /** 获取持久化的公开评价UUID，不暴露用户ID或订单主键。 */
    public String getPublicId() { return publicId; }
    /** 返回内部主键，公开投影使用publicId。 */
    public Long getId() { return id; }
    /** 返回持久门店关联，公开评价投影据此校验归属。 */
    public StoreEntity getStore() { return store; }
    /** 返回内部订单关联，仅后台资格校验使用，不公开给浏览列表。 */
    public Long getOrderId() { return orderId; }
    /** 返回已有审核显示名称，公开UUID不依赖作者名称。 */
    public String getReviewerName() { return reviewerName; }
    /** 返回现有小数评分，无客户端重新估算。 */
    public BigDecimal getRating() { return rating; }
    /** 返回持久评价内容，重复文本保留独立身份。 */
    public String getContent() { return content; }
    /** 返回UTC评价时间，客户端仅做显示时区转换。 */
    public Instant getCreatedAt() { return createdAt; }
}
