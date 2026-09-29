// 团购购买模块：封装报价的公开标识查询。
package com.fandazi.feature.purchase;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 报价仓储。 */
public interface PurchaseQuoteRepository extends JpaRepository<PurchaseQuote, Long> {
    Optional<PurchaseQuote> findByPublicId(String publicId);
}
