/* 长期登录行锁查询：多个刷新请求必须在同一数据库事务中竞争相同会话。 */
package com.fandazi.feature.auth;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 会话存储边界；普通鉴权只读，轮换和撤销使用悲观写锁。 */
public interface AuthSessionRepository extends JpaRepository<AuthSession,String> {
    /** 返回指定会话并持有写锁直到事务结束；不存在时返回空，不创建替代身份。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id=:id")
    Optional<AuthSession> findForUpdate(@Param("id") String id);
}
