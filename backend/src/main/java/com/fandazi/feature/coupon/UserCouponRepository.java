// 优惠券模块：封装用户优惠券的归属查询。
package com.fandazi.feature.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

/** 用户优惠券仓储。 */
public interface UserCouponRepository extends JpaRepository<UserCouponEntity, Long> {}
