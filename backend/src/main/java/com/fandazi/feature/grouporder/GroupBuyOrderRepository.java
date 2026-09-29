// 团购订单模块：封装用户归属和幂等键查询。
package com.fandazi.feature.grouporder;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 团购订单仓储。 */
public interface GroupBuyOrderRepository extends JpaRepository<GroupBuyOrderEntity, Long> {
    Optional<GroupBuyOrderEntity> findByPublicId(String publicId);
    Optional<GroupBuyOrderEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
