// 身份认证模块：封装持久化查询与写入。
package com.fandazi.feature.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
// 处理 `existsByUsername` 相关逻辑。
    boolean existsByUsername(String username);
// 处理 `existsByEmail` 相关逻辑。
    boolean existsByEmail(String email);
// 查询by、username。
    Optional<UserEntity> findByUsername(String username);
// 查询by、id、update。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
