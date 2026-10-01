// 团购目录模块：封装固定套餐的基础持久化查询。
package com.fandazi.feature.catalog;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 固定套餐仓储，使用 publicId 隔离数据库自增主键。 */
public interface DealPackageRepository extends JpaRepository<DealPackageEntity, Long> {
    /** 只查询本门店套餐，目录摘要循环不会重复装载整个城市所有套餐。 */
    java.util.List<DealPackageEntity> findByStoreId(Long storeId);
    Optional<DealPackageEntity> findByPublicId(String publicId);
}
