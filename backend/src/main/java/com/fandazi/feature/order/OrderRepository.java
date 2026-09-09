package com.fandazi.feature.order;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderRepository extends JpaRepository<OrderEntity,Long>{ Optional<OrderEntity> findByIdAndUserId(Long id,Long userId); Optional<OrderEntity> findByUserIdAndIdempotencyKey(Long userId,String key); }
