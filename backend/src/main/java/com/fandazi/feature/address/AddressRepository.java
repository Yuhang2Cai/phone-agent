// 收货地址模块：封装持久化查询与写入。
package com.fandazi.feature.address;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface AddressRepository extends JpaRepository<AddressEntity,Long>{ List<AddressEntity> findByUserIdOrderByDefaultAddressDescIdDesc(Long userId); Optional<AddressEntity> findByIdAndUserId(Long id,Long userId); }
