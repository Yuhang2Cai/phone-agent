// 智能助手模块：封装持久化查询与写入。
package com.fandazi.feature.agent;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    Optional<AgentSession> findBySessionId(String sessionId);
    Optional<AgentSession> findBySessionIdAndUserId(String sessionId, Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSession s where s.sessionId = :sessionId")
    Optional<AgentSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSession s where s.sessionId = :sessionId and s.user.id = :userId")
    Optional<AgentSession> findBySessionIdAndUserIdForUpdate(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
