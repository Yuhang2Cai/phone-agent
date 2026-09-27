// 商家与菜品目录模块：封装持久化查询与写入。
package com.fandazi.feature.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DishRepository extends JpaRepository<DishEntity, Long> {
    List<DishEntity> findByMerchantId(Long merchantId);

    @Modifying(flushAutomatically = true)
    @Query("update DishEntity dish set dish.stock = dish.stock - :quantity "
            + "where dish.id = :dishId and dish.stock >= :quantity")
    int decrementStockIfAvailable(@Param("dishId") Long dishId, @Param("quantity") int quantity);
}
