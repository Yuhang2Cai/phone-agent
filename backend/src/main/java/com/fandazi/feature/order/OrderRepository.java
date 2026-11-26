// 订单模块：封装持久化查询与写入。
package com.fandazi.feature.order;
import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import jakarta.persistence.LockModeType;
// 查询by、id、用户、id。
// 查询by、用户、id、订单、by、id、desc。
// 查询by、id、用户、id、update。
// 查询by、用户、id、幂等、键。
// 查询first、by、用户、id、订单、by、id、desc。
public interface OrderRepository extends JpaRepository<OrderEntity,Long>{ Optional<OrderEntity> findByIdAndUserId(Long id,Long userId); List<OrderEntity> findByUserIdOrderByIdDesc(Long userId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from OrderEntity o where o.id=:id and o.user.id=:userId") Optional<OrderEntity> findByIdAndUserIdForUpdate(@Param("id") Long id,@Param("userId") Long userId); Optional<OrderEntity> findByUserIdAndIdempotencyKey(Long userId,String key); Optional<OrderEntity> findFirstByUserIdOrderByIdDesc(Long userId); }
