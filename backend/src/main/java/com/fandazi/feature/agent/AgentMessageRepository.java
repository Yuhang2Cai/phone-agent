// Agent 消息模块：封装按会话读取消息。
package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Agent 消息仓储。 */
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {
    List<AgentMessageEntity> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);
}
