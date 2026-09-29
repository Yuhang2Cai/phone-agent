// 团购券模块：定义每个购买数量对应的独立团购券。
package com.fandazi.feature.voucher;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
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
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 团购券；状态变化只能沿后续服务定义的有限状态流转。 */
@Entity
@Table(name = "vouchers")
public class VoucherEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) private GroupBuyOrderEntity order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "deal_id", nullable = false) private DealPackageEntity deal;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private VoucherStatus status;
    @Column(name = "qr_token", nullable = false, unique = true, length = 128) private String qrToken;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "redeemed_at") private LocalDateTime redeemedAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected VoucherEntity() {}

    /** 创建一张未核销团购券。 */
    public VoucherEntity(GroupBuyOrderEntity order, UserEntity user, DealPackageEntity deal,
                         String publicId, LocalDateTime expiresAt) {
        this.order = order; this.user = user; this.deal = deal; this.publicId = publicId;
        this.qrToken = "qr-" + publicId; this.status = VoucherStatus.UNUSED;
        this.expiresAt = expiresAt; this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public GroupBuyOrderEntity getOrder() { return order; }
    public UserEntity getUser() { return user; }
    public DealPackageEntity getDeal() { return deal; }
    public VoucherStatus getStatus() { return status; }
    public String getQrToken() { return qrToken; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRedeemedAt() { return redeemedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 应用确定性演示状态。
     *
     * <p>该方法只供种子加载器恢复测试场景，真实核销和退款流程将在后续业务服务中
     * 按状态机执行，避免模型直接修改团购券状态。</p>
     */
    public void restoreSeedStatus(VoucherStatus status) {
        this.status = status;
        this.redeemedAt = status == VoucherStatus.REDEEMED ? LocalDateTime.now() : null;
    }
}
