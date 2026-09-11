package com.fandazi.feature.agent;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class AgentEventService {
    private final AgentEventRepository events; public AgentEventService(AgentEventRepository events) { this.events=events; }
    @Transactional public AgentEventEntity record(Long userId,String sessionId,String type,String summary,String toolName,String status,Map<String,Object> payload) { return events.save(new AgentEventEntity(userId,sessionId,type,summary,toolName,status,payload)); }
    @Transactional(readOnly=true) public List<AgentEventEntity> after(Long userId,String sessionId,Long lastId) { return lastId == null ? events.findByUserIdAndSessionIdOrderByIdAsc(userId,sessionId) : events.findByUserIdAndSessionIdAndIdGreaterThanOrderByIdAsc(userId,sessionId,lastId); }
}
