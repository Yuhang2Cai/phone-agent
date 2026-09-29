// 团购评价模块：封装门店评价的幂等写入和统计查询。
package com.fandazi.feature.catalog;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

/** 门店评价仓储。 */
public interface StoreReviewRepository extends JpaRepository<StoreReviewEntity, Long> {
    boolean existsByStoreIdAndReviewerNameAndCreatedAt(Long storeId, String reviewerName, Instant createdAt);
}
