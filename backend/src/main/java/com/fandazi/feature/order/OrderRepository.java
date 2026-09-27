// 订单模块：封装持久化查询与写入。
package com.fandazi.feature.order;
import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import jakarta.persistence.LockModeType;
public interface OrderRepository extends JpaRepository<OrderEntity,Long>{ Optional<OrderEntity> findByIdAndUserId(Long id,Long userId); List<OrderEntity> findByUserIdOrderByIdDesc(Long userId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from OrderEntity o where o.id=:id and o.user.id=:userId") Optional<OrderEntity> findByIdAndUserIdForUpdate(@Param("id") Long id,@Param("userId") Long userId); Optional<OrderEntity> findByUserIdAndIdempotencyKey(Long userId,String key); Optional<OrderEntity> findFirstByUserIdOrderByIdDesc(Long userId); }
