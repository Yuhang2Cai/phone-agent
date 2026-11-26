// 商家与菜品目录模块：封装持久化查询与写入。
package com.fandazi.feature.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishOptionRepository extends JpaRepository<DishOptionEntity, Long> {
// 查询by、菜品、id。
    List<DishOptionEntity> findByDishId(Long dishId);
}
