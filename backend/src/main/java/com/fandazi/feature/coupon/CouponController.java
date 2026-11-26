// 优惠券 HTTP 入口：从认证上下文获取用户，不接受客户端传入归属字段。
package com.fandazi.feature.coupon;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 给“我的优惠券”和 Agent 查询提供同一真实数据源。 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {
    private final CouponService coupons;

    /** 注入优惠券服务。 */
    public CouponController(CouponService coupons) { this.coupons = coupons; }

    /** 返回当前登录用户已领取的券。 */
    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Long userId) { return coupons.list(userId); }
}
