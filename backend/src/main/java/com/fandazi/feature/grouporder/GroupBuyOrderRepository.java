// 团购订单模块：封装用户归属和幂等键查询。
package com.fandazi.feature.grouporder;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 团购订单仓储。 */
public interface GroupBuyOrderRepository extends JpaRepository<GroupBuyOrderEntity, Long> {
    Optional<GroupBuyOrderEntity> findByPublicId(String publicId);
    Optional<GroupBuyOrderEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    /** 查询当前用户的团购订单，按创建时间倒序供订单页展示。 */
    List<GroupBuyOrderEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    /** 查询当前用户的单个团购订单，避免通过订单公开 ID 越权读取。 */
    Optional<GroupBuyOrderEntity> findByUserIdAndPublicId(Long userId, String publicId);
    /** 支付行锁防止不同会话或页面同时推进同一订单，资格与凭证校验在同一事务内完成。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from GroupBuyOrderEntity o where o.user.id = :userId and o.publicId = :publicId")
    Optional<GroupBuyOrderEntity> findOwnedForUpdate(@org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("publicId") String publicId);
}
