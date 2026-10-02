// 优惠券服务：只展示当前登录用户已领取的真实券与适用规则。
package com.fandazi.feature.coupon;

import com.fandazi.core.time.BusinessTime;
import java.time.LocalDate;
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
        // 一次列表使用同一个上海业务日，首尾日期均包含在有效期内，不能受 JVM 默认时区影响。
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        return coupons.findByUserIdOrderByClaimedAtDesc(userId).stream().map(claim -> {
            CouponEntity coupon = claim.getCoupon();
            return Map.<String, Object>of("couponId", coupon.getPublicId(), "claimId", claim.getPublicId(),
                    "name", coupon.getName(), "status", displayStatus(claim, coupon, today),
                    "thresholdCents", coupon.getThresholdCents(), "discountCents", coupon.getDiscountCents(),
                    "validFrom", coupon.getValidFrom().toString(), "validTo", coupon.getValidTo().toString(),
                    "scopeDescription", coupon.getScopeDescription());
        }).toList();
    }

    /** 从领取事实和当前规则计算只读状态；不回写记录，也不授权领取、抵扣或核销。 */
    private String displayStatus(UserCouponEntity claim, CouponEntity coupon, LocalDate today) {
        // 已使用/已持久化过期是历史终态，不能因规则或日期变化恢复为可用。
        if (claim.getStatus() != CouponStatus.AVAILABLE) return claim.getStatus().name();
        // 到期优先于当前开关；尚未到期的停用券与未来券分别说明真正不可用原因。
        if (today.isAfter(coupon.getValidTo())) return "EXPIRED";
        if (!coupon.isActive()) return "DISABLED";
        if (today.isBefore(coupon.getValidFrom())) return "NOT_YET_VALID";
        return "AVAILABLE";
    }
}
