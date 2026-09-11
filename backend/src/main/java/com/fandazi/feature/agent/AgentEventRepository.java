package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentEventRepository extends JpaRepository<AgentEventEntity, Long> {
    List<AgentEventEntity> findByUserIdAndSessionIdOrderByIdAsc(Long userId, String sessionId);
    List<AgentEventEntity> findByUserIdAndSessionIdAndIdGreaterThanOrderByIdAsc(Long userId, String sessionId, Long id);
}
