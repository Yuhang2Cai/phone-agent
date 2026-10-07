// 优惠券模块：保存用户领取的优惠券和使用状态。
package com.fandazi.feature.coupon;

import com.fandazi.feature.auth.UserEntity;
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

/** 用户和优惠券定义之间的领取关系。 */
@Entity
@Table(name = "user_coupons")
public class UserCouponEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "coupon_id", nullable = false) private CouponEntity coupon;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private CouponStatus status;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "claimed_at", nullable = false) private LocalDateTime claimedAt;
    @Column(name = "used_at") private LocalDateTime usedAt;

    protected UserCouponEntity() {}

    /** 创建一条可用的用户优惠券记录。 */
    public UserCouponEntity(String publicId, UserEntity user, CouponEntity coupon) {
        this.publicId = publicId; this.user = user; this.coupon = coupon;
        this.status = CouponStatus.AVAILABLE; this.claimedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public UserEntity getUser() { return user; }
    public CouponEntity getCoupon() { return coupon; }
    public CouponStatus getStatus() { return status; }
    public Long getOrderId() { return orderId; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
}
