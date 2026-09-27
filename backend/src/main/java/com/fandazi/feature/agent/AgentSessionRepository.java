// 智能助手模块：封装持久化查询与写入。
package com.fandazi.feature.agent;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
// 查询by、会话、id。
    Optional<AgentSession> findBySessionId(String sessionId);
// 查询by、会话、id、用户、id。
    Optional<AgentSession> findBySessionIdAndUserId(String sessionId, Long userId);
// 查询by、会话、id、update。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSession s where s.sessionId = :sessionId")
    Optional<AgentSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);
// 查询by、会话、id、用户、id、update。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSession s where s.sessionId = :sessionId and s.user.id = :userId")
    Optional<AgentSession> findBySessionIdAndUserIdForUpdate(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
