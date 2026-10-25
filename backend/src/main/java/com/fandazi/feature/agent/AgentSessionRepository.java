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
    /** 仅检查是否存在，避免在锁读之前把旧快照实体装入持久化上下文。 */
    boolean existsBySessionId(String sessionId);
// 查询by、会话、id、用户、id。
    Optional<AgentSession> findBySessionIdAndUserId(String sessionId, Long userId);
    /**
     * 在调用者事务内按唯一session_id原子建立空会话；冲突分支只保留原键，绝不覆盖归属或业务状态。
     * 调用前不能对不存在的会话执行悲观查询，否则MySQL RR间隙锁可能与并发插入互等。
     * 返回影响行数仅为驱动结果，不用于判断归属/是否首建；调用方随后锁读并核验原记录。
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true)
    @Query(value="""
        INSERT INTO agent_sessions
          (user_id,session_id,mode,trace_id,confirmed,confirmation_consumed,created_order_settled,
           conversation_turn_sequence,task_focus_version,task_event_sequence)
        VALUES (:userId,:sessionId,:mode,:traceId,false,false,false,0,0,0)
        ON DUPLICATE KEY UPDATE session_id = session_id
        """,nativeQuery=true)
    int createIfAbsent(@Param("userId") Long userId,@Param("sessionId") String sessionId,
                       @Param("mode") String mode,@Param("traceId") String traceId);

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
