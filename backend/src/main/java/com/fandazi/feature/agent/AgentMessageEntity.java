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
@Table(name = "agent_messages", uniqueConstraints = @jakarta.persistence.UniqueConstraint(name="ux_agent_message_record",columnNames="record_key"))
public class AgentMessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(name = "trace_id", length = 100) private String traceId;
    @Column(name = "run_id", length = 100) private String runId;
    @Column(nullable = false, length = 40) private String role;
    // 完整正文不得截断；LONGTEXT与V16保持一致，旧消息无需重写。
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String content;
    @Column(name="client_message_key",length=100) private String clientMessageKey;
    @Column(name="record_key",length=64) private String recordKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AgentMessageEntity() {}

    /** 创建一条带链路标识的会话消息。 */
    public AgentMessageEntity(String publicId, UserEntity user, String sessionId, String traceId,
                              String runId, String role, String content, Instant createdAt) {
        this.publicId = publicId; this.user = user; this.sessionId = sessionId; this.traceId = traceId;
        this.runId = runId; this.role = role; this.content = content; this.createdAt = createdAt;
    }

    /** 新记录保存客户端展示键与服务端写入唯一键；两个键都不参与交易授权或业务幂等。 */
    public AgentMessageEntity(String publicId,UserEntity user,String sessionId,String traceId,String runId,String role,String content,Instant createdAt,String clientMessageKey,String recordKey) {
        this(publicId,user,sessionId,traceId,runId,role,content,createdAt);
        this.clientMessageKey=clientMessageKey;this.recordKey=recordKey;
    }
    /** 可空客户端展示关联，旧记录保留null，不依据正文猜用户输入身份。 */
    public String getClientMessageKey() { return clientMessageKey; }
    /** 仅数据库防重使用的服务端记录摘要，历史公开响应不会返回该字段。 */
    public String getRecordKey() { return recordKey; }

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
