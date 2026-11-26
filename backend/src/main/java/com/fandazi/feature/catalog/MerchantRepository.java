// 商家与菜品目录模块：封装持久化查询与写入。
package com.fandazi.feature.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MerchantRepository extends JpaRepository<MerchantEntity, Long> {}
