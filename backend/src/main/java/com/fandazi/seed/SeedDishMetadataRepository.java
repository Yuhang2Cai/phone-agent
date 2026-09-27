// 演示数据模块：封装持久化查询与写入。
package com.fandazi.seed;
import org.springframework.data.jpa.repository.JpaRepository;
// 查询by、菜品、id。
public interface SeedDishMetadataRepository extends JpaRepository<SeedDishMetadataEntity, Long> { java.util.Optional<SeedDishMetadataEntity> findByDishId(Long dishId); }
