// Agent 消息模块：保存聊天消息索引，原始业务事实仍由 Spring 业务表负责。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Agent 聊天消息。
 *
 * <p>该表用于 Spring 侧的会话恢复和事件关联，不把它当作 Agent 长期记忆；
 * 长期偏好由独立 Agent 服务的 memory_items 管理。</p>
 */
@Entity
@Table(name = "agent_messages")
public class AgentMessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(name = "trace_id", nullable = false, length = 100) private String traceId;
    @Column(name = "run_id", nullable = false, length = 100) private String runId;
    @Column(nullable = false, length = 40) private String role;
    @Column(nullable = false, length = 8000) private String content;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AgentMessageEntity() {}

    /** 创建一条带链路标识的会话消息。 */
    public AgentMessageEntity(String publicId, UserEntity user, String sessionId, String traceId,
                              String runId, String role, String content, Instant createdAt) {
        this.publicId = publicId; this.user = user; this.sessionId = sessionId; this.traceId = traceId;
        this.runId = runId; this.role = role; this.content = content; this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public UserEntity getUser() { return user; }
    public String getSessionId() { return sessionId; }
    public String getTraceId() { return traceId; }
    public String getRunId() { return runId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
