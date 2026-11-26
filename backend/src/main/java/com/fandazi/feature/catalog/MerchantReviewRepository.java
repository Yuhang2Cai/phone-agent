// 商家与菜品目录模块：封装持久化查询与写入。
package com.fandazi.feature.catalog;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantReviewRepository extends JpaRepository<MerchantReviewEntity, Long> {
// 查询by、商家、id。
    Page<MerchantReviewEntity> findByMerchantId(Long merchantId, Pageable pageable);
// 处理 `existsByMerchantIdAndReviewerNameAndCreatedAt` 相关逻辑。
    boolean existsByMerchantIdAndReviewerNameAndCreatedAt(Long merchantId, String reviewerName, Instant createdAt);
}
