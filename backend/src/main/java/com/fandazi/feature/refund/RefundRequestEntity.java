// 售后模块：保存团购券退款申请和用户提交的原因。
package com.fandazi.feature.refund;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.voucher.VoucherEntity;
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

/** 尚未核销团购券的退款申请。 */
@Entity
@Table(name = "refund_requests", uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "uk_refund_requests_voucher", columnNames = "voucher_id"))
public class RefundRequestEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "voucher_id", nullable = false) private VoucherEntity voucher;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(nullable = false, length = 500) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private RefundStatus status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    // 申请时保存已支付券金额，历史申请详情不依赖随后变化的套餐价格或券展示逻辑。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "amount", nullable = false, precision = 12, scale = 2) private int amountCents;

    protected RefundRequestEntity() {}

    /** 创建一条待处理退款申请。 */
    public RefundRequestEntity(String publicId, VoucherEntity voucher, UserEntity user, String reason) {
        this.publicId = publicId; this.voucher = voucher; this.user = user; this.reason = reason;
        if (voucher.getAmountCents() == null) throw new IllegalArgumentException("未付款券不能建立退款金额快照");
        this.amountCents = voucher.getAmountCents();
        this.status = RefundStatus.REQUESTED; this.createdAt = com.fandazi.core.time.BusinessTime.now();
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public VoucherEntity getVoucher() { return voucher; }
    public UserEntity getUser() { return user; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 返回申请建立时的真实整数分金额，不查询现价。 */
    public int getAmountCents() { return amountCents; }
}
