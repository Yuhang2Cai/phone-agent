// 工具审计模块：业务事务提交后独立记录公开结果，审计数据库失败不能阻塞工具路由。
package com.fandazi.feature.agent;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
                // 接收审计携带动作版本及键摘要；重试可能重复接收，业务执行次数仍以幂等表核查。
                // 不公开原始幂等键或准备凭证，关联摘要不能被当作交易授权。
                Map.of("runId", request.runId(), "traceId", request.traceId(), "taskId", request.taskId(),
                        "actionVersion", request.version(), "executionKeyHash", executionKeyHash(request.idempotencyKey()), "result", result));
    }
    /** v2接收审计带task/turn/代次与原执行键摘要；复用递归脱敏，不把grant写入公开历史。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordTask(InternalV2ToolRequest request,String tool,Map<String,Object> result){
        events.record(Long.valueOf(request.userId()),request.sessionId(),"tool_result","工具执行结果",tool,
            Boolean.TRUE.equals(result.get("ok"))?"succeeded":"failed",Map.of("runId",request.runId(),"traceId",request.traceId(),"taskId",request.taskId(),
                "conversationTaskId",request.taskFence().get("conversationTaskId"),"turnId",request.taskFence().get("turnId"),
                "executionGeneration",request.taskFence().get("executionGeneration"),"actionVersion",request.actionVersion(),"executionKeyHash",executionKeyHash(request.idempotencyKey()),"result",result));
    }
    /** 只对受控执行器键做SHA-256摘要，不读取业务载荷；标准算法缺失时明确失败由外层审计降级。 */
    private static String executionKeyHash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("工具证据摘要算法不可用",exception); }
    }
}
