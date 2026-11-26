// 商家与菜品目录模块：定义持久化数据结构。
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

@Entity
@Table(name = "merchant_reviews")
public class MerchantReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private MerchantEntity merchant;

    @Column(nullable = false, length = 80)
    private String reviewerName;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

// 初始化对象依赖与默认状态。
    protected MerchantReviewEntity() {}

// 初始化对象依赖与默认状态。
    public MerchantReviewEntity(MerchantEntity merchant, String reviewerName, BigDecimal rating, String content, Instant createdAt) {
        this.merchant = merchant;
        this.reviewerName = reviewerName;
        this.rating = rating;
        this.content = content;
        this.createdAt = createdAt;
    }

// 获取id。
    public Long getId() { return id; }
// 获取商家。
    public MerchantEntity getMerchant() { return merchant; }
// 获取reviewer、name。
    public String getReviewerName() { return reviewerName; }
// 获取rating。
    public BigDecimal getRating() { return rating; }
// 获取content。
    public String getContent() { return content; }
// 获取created、at。
    public Instant getCreatedAt() { return createdAt; }
}
