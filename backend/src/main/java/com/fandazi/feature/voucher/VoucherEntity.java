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
    // 券金额由订单支付实付分摊，未付款为 null；退款只读取此列，不对现价重复计算。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "allocated_amount", precision = 12, scale = 2) private Integer amountCents;

    protected VoucherEntity() {}

    /** 创建一张未核销团购券。 */
    public VoucherEntity(GroupBuyOrderEntity order, UserEntity user, DealPackageEntity deal,
                         String publicId, LocalDateTime expiresAt) {
        this.order = order; this.user = user; this.deal = deal; this.publicId = publicId;
        this.qrToken = "qr-" + publicId; this.status = VoucherStatus.UNUSED;
        this.expiresAt = expiresAt; this.createdAt = com.fandazi.core.time.BusinessTime.now();
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
    /** 返回该券实付分摊整数分，订单未支付为 null。 */
    public Integer getAmountCents() { return amountCents; }

    /** 仅订单支付聚合调用；已确定金额不能改写，负数或改价抛出异常阻止退款快照漂移。 */
    public void capturePaymentAmount(int cents) {
        if (cents < 0) throw new IllegalArgumentException("券实付金额不能为负数");
        if (amountCents != null && amountCents != cents) throw new IllegalStateException("券支付快照不可修改");
        amountCents = cents;
    }

    /**
     * 应用确定性演示状态。
     *
     * <p>该方法只供种子加载器恢复测试场景，真实核销和退款流程将在后续业务服务中
     * 按状态机执行，避免模型直接修改团购券状态。</p>
     */
    public void restoreSeedStatus(VoucherStatus status) {
        this.status = status;
        this.redeemedAt = status == VoucherStatus.REDEEMED ? com.fandazi.core.time.BusinessTime.now() : null;
    }

    /**
     * 在门店核对通过后完成核销。
     *
     * @param storePublicId 当前核销门店的公开标识
     * @return 是否发生了首次核销；重复提交返回 false，保证演示接口幂等
     * @throws IllegalStateException 券不属于该门店或当前状态不可核销
     */
    public boolean redeem(String storePublicId) {
        if (!deal.getStore().getPublicId().equals(storePublicId)) {
            throw new IllegalStateException("核销门店与团购券不匹配");
        }
        // 已核销请求重复到达时直接返回幂等结果，不重复修改时间和订单统计。
        if (status == VoucherStatus.REDEEMED) return false;
        if (status != VoucherStatus.UNUSED) {
            throw new IllegalStateException("团购券当前不可核销");
        }
        status = VoucherStatus.REDEEMED;
        redeemedAt = com.fandazi.core.time.BusinessTime.now();
        // 券是订单的聚合子项，首次核销完成后同步订单的部分使用或全部使用状态。
        order.refreshUsageStatus();
        return true;
    }
}
