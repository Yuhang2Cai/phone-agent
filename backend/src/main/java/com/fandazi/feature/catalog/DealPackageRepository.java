// 团购目录模块：封装固定套餐的基础持久化查询。
package com.fandazi.feature.catalog;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 固定套餐仓储，使用 publicId 隔离数据库自增主键。 */
public interface DealPackageRepository extends JpaRepository<DealPackageEntity, Long> {
    Optional<DealPackageEntity> findByPublicId(String publicId);
}
