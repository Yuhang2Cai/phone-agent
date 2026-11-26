// 优惠券模块：封装优惠券定义查询。
package com.fandazi.feature.coupon;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 优惠券定义仓储。 */
public interface CouponRepository extends JpaRepository<CouponEntity, Long> {
    Optional<CouponEntity> findByPublicId(String publicId);
}
