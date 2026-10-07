// 团购目录模块：封装门店的基础持久化查询。
package com.fandazi.feature.catalog;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 门店仓储，后续查询服务按公开标识访问门店。 */
public interface StoreRepository extends JpaRepository<StoreEntity, Long> {
    Optional<StoreEntity> findByPublicId(String publicId);
}
