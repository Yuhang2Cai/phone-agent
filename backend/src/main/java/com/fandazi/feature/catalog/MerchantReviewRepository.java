// 商家与菜品目录模块：封装持久化查询与写入。
package com.fandazi.feature.catalog;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantReviewRepository extends JpaRepository<MerchantReviewEntity, Long> {
    Page<MerchantReviewEntity> findByMerchantId(Long merchantId, Pageable pageable);
    boolean existsByMerchantIdAndReviewerNameAndCreatedAt(Long merchantId, String reviewerName, Instant createdAt);
}
