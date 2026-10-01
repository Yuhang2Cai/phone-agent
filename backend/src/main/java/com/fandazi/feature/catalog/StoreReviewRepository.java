// 团购评价模块：封装门店评价的幂等写入和统计查询。
package com.fandazi.feature.catalog;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

/** 门店评价仓储。 */
public interface StoreReviewRepository extends JpaRepository<StoreReviewEntity, Long> {
    /** 页面详情读取单门店全量评价，避免为一个摘要装载其他门店评价。 */
    java.util.List<StoreReviewEntity> findByStoreId(Long storeId);
    /** 目录一次批量读取候选门店评价；在现有集合查询上聚合，避免每个门店反复全表扫描。 */
    java.util.List<StoreReviewEntity> findByStoreIdIn(java.util.Collection<Long> storeIds);
    boolean existsByStoreIdAndReviewerNameAndCreatedAt(Long storeId, String reviewerName, Instant createdAt);
    /** 同一团购订单最多一条用户评价。 */
    boolean existsByOrderId(Long orderId);
}
