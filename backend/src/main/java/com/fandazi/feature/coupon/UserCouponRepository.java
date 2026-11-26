// 优惠券模块：封装用户优惠券的归属查询。
package com.fandazi.feature.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

/** 用户优惠券仓储；查询必须附带认证用户 ID。 */
public interface UserCouponRepository extends JpaRepository<UserCouponEntity, Long> {
    /** 按领取时间倒序读取当前用户的券。 */
    java.util.List<UserCouponEntity> findByUserIdOrderByClaimedAtDesc(Long userId);
}
