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
// 初始化对象依赖与默认状态。
    protected AgentEventEntity() {}
// 初始化对象依赖与默认状态。
    AgentEventEntity(Long userId, String sessionId, String type, String summary, String toolName, String status, Map<String, Object> payload) {
        this.userId=userId; this.sessionId=sessionId; this.type=type; this.summary=summary; this.toolName=toolName; this.status=status;
        // JSON 空值表示成功工具无错误，必须原样持久化；Map.copyOf 会因 null 抛异常。
        this.payload = payload == null ? Map.of() : new java.util.LinkedHashMap<>(payload);
        this.createdAt=Instant.now();
    }
// 获取id。
    // 获取用户、id。
    // 获取会话、id。
    // 获取type。
    // 获取summary。
    // 获取工具、name。
    // 获取status。
    // 获取payload。
    // 获取created、at。
    public Long getId(){return id;} public Long getUserId(){return userId;} public String getSessionId(){return sessionId;} public String getType(){return type;} public String getSummary(){return summary;} public String getToolName(){return toolName;} public String getStatus(){return status;}
    /** 返回保留合法空值的只读副本，调用方不能修改托管实体的事件载荷。 */
    public Map<String,Object> getPayload(){return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));}
    public Instant getCreatedAt(){return createdAt;}
}
