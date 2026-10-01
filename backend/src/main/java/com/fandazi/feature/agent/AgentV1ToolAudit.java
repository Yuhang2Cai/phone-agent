// 工具审计模块：业务事务提交后独立记录公开结果，审计数据库失败不能阻塞工具路由。
package com.fandazi.feature.agent;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立事务把审计失败与已提交业务隔离；AgentEventService 已递归剔除所有内部确认凭证。 */
@Service
class AgentV1ToolAudit {
    private final AgentEventService events;
    /** 注入现有公开审计服务，复用递归脱敏规则。 */
    AgentV1ToolAudit(AgentEventService events) { this.events = events; }
    /** 成功与失败都记录动作身份和安全结果，不记录 JWT 或完整请求；失败由控制器记录可定位日志。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(InternalV1ToolRequest request, String tool, Map<String, Object> result) {
        events.record(Long.valueOf(request.userId()), request.sessionId(), "tool_result", "工具执行结果", tool,
                Boolean.TRUE.equals(result.get("ok")) ? "succeeded" : "failed",
                Map.of("runId", request.runId(), "traceId", request.traceId(), "taskId", request.taskId(), "result", result));
    }
}
