// 工具执行持久化模块：记录执行器动作键、请求指纹及内部结果，跨重启重试不会重复业务写入。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

/** 仅供内部重放的执行记录；结果可能含确认凭证，公开审计必须另经脱敏器。 */
@Entity
@Table(name = "agent_tool_executions")
class AgentToolExecution {
    @Id @Column(name = "execution_key", length = 255) private String executionKey;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(name = "tool_name", nullable = false, length = 80) private String toolName;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Convert(converter = StringMapJsonConverter.class) @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private Map<String, Object> result;
    /** JPA 使用空构造器恢复已经提交的记录。 */
    protected AgentToolExecution() {}
    /** 保存完整成功结果；事务提交前由契约校验，失败动作不占用成功幂等键。 */
    AgentToolExecution(InternalV1ToolRequest request, Long userId, String tool, String fingerprint, Map<String, Object> result) {
        this.executionKey = request.idempotencyKey(); this.userId = userId; this.sessionId = request.sessionId();
        this.toolName = tool; this.fingerprint = fingerprint; this.result = result; this.createdAt = Instant.now();
    }
    /** 返回原请求摘要供重试核对，任何载荷变化都不能冒充原动作。 */
    String getFingerprint() { return fingerprint; }
    /** 返回实际已提交结果；重放不再次消费凭证，也不执行领域写入。 */
    Map<String, Object> getResult() { return result; }
}
