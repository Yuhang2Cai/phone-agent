// 团购订单模块：定义购买固定套餐后的订单和一券一数量规则。
package com.fandazi.feature.grouporder;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.StoreEntity;
import com.fandazi.feature.voucher.VoucherEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 到店团购订单。
 *
 * <p>订单创建时按购买数量一次性建立团购券集合，使后续部分核销、单券退款和
 * 评价资格都能在券粒度上判断。</p>
 */
@Entity
@Table(name = "group_buy_orders", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
public class GroupBuyOrderEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "store_id", nullable = false) private StoreEntity store;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "deal_id", nullable = false) private DealPackageEntity deal;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(nullable = false) private int quantity;
    @Column(name = "total_cents", nullable = false) private int totalCents;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private GroupBuyOrderStatus status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "request_fingerprint", nullable = false, length = 128) private String requestFingerprint;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<VoucherEntity> vouchers = new ArrayList<>();

    protected GroupBuyOrderEntity() {}

    /**
     * 创建团购订单并生成与数量相等的未使用券。
     *
     * @param publicId 订单公开标识
     * @param user 下单用户
     * @param store 购买门店
     * @param deal 固定套餐
     * @param quantity 购买数量，必须为正数
     * @param totalCents 订单总金额，单位为分
     * @param status 初始订单状态
     * @param expiresAt 订单或券的有效截止时间
     * @throws IllegalArgumentException 数量不为正或金额为负时抛出
     */
    public GroupBuyOrderEntity(String publicId, UserEntity user, StoreEntity store, DealPackageEntity deal,
                               int quantity, int totalCents, GroupBuyOrderStatus status, LocalDateTime expiresAt) {
        this(publicId, user, store, deal, publicId, quantity, totalCents, status, expiresAt, "seed-or-domain");
    }

    /** 创建带业务幂等键和请求指纹的订单，供真实购买写流程使用。 */
    public GroupBuyOrderEntity(String publicId, UserEntity user, StoreEntity store, DealPackageEntity deal, String idempotencyKey,
                               int quantity, int totalCents, GroupBuyOrderStatus status, LocalDateTime expiresAt, String requestFingerprint) {
        if (quantity <= 0) throw new IllegalArgumentException("团购数量必须大于零");
        if (totalCents < 0) throw new IllegalArgumentException("团购金额不能为负数");
        this.publicId = publicId; this.user = user; this.store = store; this.deal = deal;
        this.idempotencyKey = idempotencyKey; this.quantity = quantity; this.totalCents = totalCents;
        this.status = status; this.createdAt = LocalDateTime.now(); this.expiresAt = expiresAt;
        this.requestFingerprint = requestFingerprint;

        // 每一个购买数量都生成一张独立团购券，后续可以分别核销或退款。
        for (int index = 0; index < quantity; index++) {
            vouchers.add(new VoucherEntity(this, user, deal, "voucher-" + UUID.randomUUID(), expiresAt));
        }
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public UserEntity getUser() { return user; }
    public StoreEntity getStore() { return store; }
    public DealPackageEntity getDeal() { return deal; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getQuantity() { return quantity; }
    public int getTotalCents() { return totalCents; }
    public GroupBuyOrderStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public List<VoucherEntity> getVouchers() { return List.copyOf(vouchers); }

    /** 将待支付订单推进为已支付，重复调用保持幂等。 */
    public void markPaid() {
        if (status == GroupBuyOrderStatus.PAID) return;
        if (status != GroupBuyOrderStatus.PENDING_PAYMENT) throw new IllegalStateException("订单当前不可支付");
        status = GroupBuyOrderStatus.PAID;
    }

    /**
     * 根据当前券集合刷新订单使用状态。
     * 核销发生在券粒度，订单状态只是聚合展示，因此必须由订单实体统一计算，不能由客户端传入。
     */
    public void refreshUsageStatus() {
        long redeemed = vouchers.stream().filter(voucher -> voucher.getStatus() == com.fandazi.feature.voucher.VoucherStatus.REDEEMED).count();
        if (redeemed == 0) return;
        status = redeemed == vouchers.size() ? GroupBuyOrderStatus.USED : GroupBuyOrderStatus.PARTIALLY_USED;
    }
}
