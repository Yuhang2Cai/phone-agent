// 演示数据模块：封装持久化查询与写入。
package com.fandazi.seed;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SeedAddressMetadataRepository extends JpaRepository<SeedAddressMetadataEntity, Long> { java.util.Optional<SeedAddressMetadataEntity> findByAddressId(Long addressId); }
