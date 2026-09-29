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
@Table(name = "refund_requests")
public class RefundRequestEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "voucher_id", nullable = false) private VoucherEntity voucher;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(nullable = false, length = 500) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private RefundStatus status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected RefundRequestEntity() {}

    /** 创建一条待处理退款申请。 */
    public RefundRequestEntity(String publicId, VoucherEntity voucher, UserEntity user, String reason) {
        this.publicId = publicId; this.voucher = voucher; this.user = user; this.reason = reason;
        this.status = RefundStatus.REQUESTED; this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public VoucherEntity getVoucher() { return voucher; }
    public UserEntity getUser() { return user; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
