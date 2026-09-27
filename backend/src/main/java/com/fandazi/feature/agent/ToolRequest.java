// 智能助手模块：定义请求数据及其边界。
package com.fandazi.feature.agent;
import java.util.Map;
public record ToolRequest(String userId,String sessionId,String traceId,String idempotencyKey,String workflowId,Map<String,Object> payload) {
    public ToolRequest { payload=payload==null?Map.of():Map.copyOf(payload); }
}
