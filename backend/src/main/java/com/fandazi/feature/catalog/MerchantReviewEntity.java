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

    protected MerchantReviewEntity() {}

    public MerchantReviewEntity(MerchantEntity merchant, String reviewerName, BigDecimal rating, String content, Instant createdAt) {
        this.merchant = merchant;
        this.reviewerName = reviewerName;
        this.rating = rating;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public MerchantEntity getMerchant() { return merchant; }
    public String getReviewerName() { return reviewerName; }
    public BigDecimal getRating() { return rating; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
