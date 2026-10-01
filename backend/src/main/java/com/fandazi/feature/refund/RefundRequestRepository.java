// 售后模块：封装退款申请持久化。
package com.fandazi.feature.refund;

import org.springframework.data.jpa.repository.JpaRepository;

/** 退款申请仓储；按券去重，避免同一张券同时存在两笔申请。 */
public interface RefundRequestRepository extends JpaRepository<RefundRequestEntity, Long> {
    /** 检查当前券是否已申请退款。 */
    boolean existsByVoucherId(Long voucherId);
    /** 查询当前用户的退款进度，不允许只凭申请编号读取他人记录。 */
    java.util.Optional<RefundRequestEntity> findByUserIdAndPublicId(Long userId, String publicId);
}
