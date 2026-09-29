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
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "store_id", nullable = false) private StoreEntity store;
    @Column(name = "reviewer_name", nullable = false, length = 80) private String reviewerName;
    @Column(nullable = false, precision = 2, scale = 1) private BigDecimal rating;
    @Column(nullable = false, length = 1000) private String content;
    @Column(nullable = false) private Instant createdAt;

    protected StoreReviewEntity() {}

    /** 创建一条门店评价，评分范围由种子加载器和后续服务共同校验。 */
    public StoreReviewEntity(StoreEntity store, String reviewerName, BigDecimal rating, String content, Instant createdAt) {
        this.store = store; this.reviewerName = reviewerName; this.rating = rating; this.content = content; this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public StoreEntity getStore() { return store; }
    public String getReviewerName() { return reviewerName; }
    public BigDecimal getRating() { return rating; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
