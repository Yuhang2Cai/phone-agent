// 团购购买模块：定义短期报价的持久化结构和金额快照。
package com.fandazi.feature.purchase;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.catalog.DealPackageEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 购买报价。
 *
 * <p>报价保存计算时的金额和套餐版本，过期后不能直接创建订单，避免模型或客户端
 * 重放旧价格。</p>
 */
@Entity
@Table(name = "purchase_quotes")
public class PurchaseQuote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "deal_id", nullable = false) private DealPackageEntity deal;
    @Column(nullable = false) private int quantity;
    @Column(name = "subtotal_cents", nullable = false) private int subtotalCents;
    @Column(name = "discount_cents", nullable = false) private int discountCents;
    @Column(name = "total_cents", nullable = false) private int totalCents;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(nullable = false, length = 40) private String status;
    @Column(name = "request_fingerprint", nullable = false, length = 128) private String requestFingerprint;

    protected PurchaseQuote() {}

    /** 创建一份带有效期的金额快照。 */
    public PurchaseQuote(String publicId, UserEntity user, DealPackageEntity deal, int quantity, int subtotalCents,
                         int discountCents, int totalCents, Instant expiresAt, Instant createdAt,
                         String status, String requestFingerprint) {
        this.publicId = publicId; this.user = user; this.deal = deal; this.quantity = quantity;
        this.subtotalCents = subtotalCents; this.discountCents = discountCents; this.totalCents = totalCents;
        this.expiresAt = expiresAt; this.createdAt = createdAt; this.status = status; this.requestFingerprint = requestFingerprint;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public UserEntity getUser() { return user; }
    public DealPackageEntity getDeal() { return deal; }
    public int getQuantity() { return quantity; }
    public int getSubtotalCents() { return subtotalCents; }
    public int getDiscountCents() { return discountCents; }
    public int getTotalCents() { return totalCents; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getRequestFingerprint() { return requestFingerprint; }
}
