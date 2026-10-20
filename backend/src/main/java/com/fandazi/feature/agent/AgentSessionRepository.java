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
    /** 锁定单个会话；归属从会话表只读外键取得，禁止 JOIN 后把同用户的独立会话串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSession s where s.sessionId = :sessionId")
    Optional<AgentSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

// 查询by、会话、id、用户、id、update。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AgentSession s where s.sessionId = :sessionId and s.user.id = :userId")
    Optional<AgentSession> findBySessionIdAndUserIdForUpdate(@Param("sessionId") String sessionId, @Param("userId") Long userId);
    /**
     * 出口在一次锁读中同时核验会话归属、当前 epoch 与可信输入来源。
     * 返回的受管会话供展示失效、确认摘要和终态共用，避免同一事务再次查询相同行。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from AgentSession session, AgentConversationSource source
        where session.sessionId = :sessionId and session.ownerId = :userId
          and source.sourceId = :sourceId and source.userId = :userId
          and source.sessionId = :sessionId and source.epoch = session.conversationEpoch
        """)
    Optional<AgentSession> findOwnedSourceForUpdate(@Param("userId") Long userId,
            @Param("sessionId") String sessionId,@Param("sourceId") String sourceId);
}
