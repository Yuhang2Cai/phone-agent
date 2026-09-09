package com.fandazi.feature.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishOptionRepository extends JpaRepository<DishOptionEntity, Long> {
    List<DishOptionEntity> findByDishId(Long dishId);
}
