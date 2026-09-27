// 智能助手模块：定义持久化数据结构。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

@Entity @Table(name = "agent_events")
public class AgentEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId; @Column(nullable = false, length = 100) private String sessionId;
    @Column(nullable = false, length = 40) private String type; @Column(nullable = false, length = 1000) private String summary;
    @Column(length = 100) private String toolName; @Column(nullable = false, length = 40) private String status;
    @Convert(converter = StringMapJsonConverter.class) @Column(nullable = false, length = 8000) private Map<String, Object> payload;
    @Column(nullable = false) private Instant createdAt;
    protected AgentEventEntity() {}
    AgentEventEntity(Long userId, String sessionId, String type, String summary, String toolName, String status, Map<String, Object> payload) {
        this.userId=userId; this.sessionId=sessionId; this.type=type; this.summary=summary; this.toolName=toolName; this.status=status; this.payload=payload == null ? Map.of() : Map.copyOf(payload); this.createdAt=Instant.now();
    }
    public Long getId(){return id;} public Long getUserId(){return userId;} public String getSessionId(){return sessionId;} public String getType(){return type;} public String getSummary(){return summary;} public String getToolName(){return toolName;} public String getStatus(){return status;} public Map<String,Object> getPayload(){return Map.copyOf(payload);} public Instant getCreatedAt(){return createdAt;}
}
