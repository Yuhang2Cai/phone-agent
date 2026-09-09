package com.fandazi.feature.catalog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DishRepository extends JpaRepository<DishEntity, Long> { List<DishEntity> findByMerchantId(Long merchantId); }
