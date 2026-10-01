// 优惠券服务：只展示当前登录用户已领取的真实券与适用规则。
package com.fandazi.feature.coupon;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户券查询不根据模型文本推测是否已领取，也不暴露其他用户券。 */
@Service
public class CouponService {
    private final UserCouponRepository coupons;

    /** 注入按用户归属查询的仓储。 */
    public CouponService(UserCouponRepository coupons) { this.coupons = coupons; }

    /** 返回本人券状态、门槛和有效期，金额始终以分为单位。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId) {
        return coupons.findByUserIdOrderByClaimedAtDesc(userId).stream().map(claim -> {
            CouponEntity coupon = claim.getCoupon();
            return Map.<String, Object>of("couponId", coupon.getPublicId(), "claimId", claim.getPublicId(),
                    "name", coupon.getName(), "status", claim.getStatus().name(),
                    "thresholdCents", coupon.getThresholdCents(), "discountCents", coupon.getDiscountCents(),
                    "validFrom", coupon.getValidFrom().toString(), "validTo", coupon.getValidTo().toString(),
                    "scopeDescription", coupon.getScopeDescription());
        }).toList();
    }
}
