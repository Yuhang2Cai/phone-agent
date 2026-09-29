// 团购券模块：封装用户和公开标识查询。
package com.fandazi.feature.voucher;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 团购券仓储。 */
public interface VoucherRepository extends JpaRepository<VoucherEntity, Long> {
    Optional<VoucherEntity> findByPublicId(String publicId);
    long countByStatus(VoucherStatus status);
}
