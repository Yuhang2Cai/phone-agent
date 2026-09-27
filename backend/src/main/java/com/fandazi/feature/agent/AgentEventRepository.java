// 智能助手模块：封装持久化查询与写入。
package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentEventRepository extends JpaRepository<AgentEventEntity, Long> {
// 查询by、用户、id、会话、id、订单、by、id、asc。
    List<AgentEventEntity> findByUserIdAndSessionIdOrderByIdAsc(Long userId, String sessionId);
// 查询by、用户、id、会话、id、id、greater、than、订单、by、id、asc。
    List<AgentEventEntity> findByUserIdAndSessionIdAndIdGreaterThanOrderByIdAsc(Long userId, String sessionId, Long id);
}
