// v2工具传输模块：完整Schema校验后转换，沿用17工具载荷，独立绑定不可变TaskFence。
package com.fandazi.feature.agent;
import java.util.Map;

/** taskId为DAG节点，conversationTaskId在taskFence内；动作键不允许由模型业务载荷指定。 */
public record InternalV2ToolRequest(String schemaVersion,String userId,String sessionId,String traceId,String runId,
        String taskId,String idempotencyKey,Integer actionVersion,Map<String,Object> payload,Map<String,Object> taskFence) {
    /** 领域唯一分发只消费既有载荷结构，传输转换不会调用无栅栏v1执行入口。 */
    InternalV1ToolRequest domainRequest(){return new InternalV1ToolRequest("1.1",userId,sessionId,traceId,runId,taskId,idempotencyKey,actionVersion,payload);}
}
