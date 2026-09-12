package com.fandazi.feature.agent;
import java.util.Map;
public record ToolRequest(String userId,String sessionId,String traceId,String idempotencyKey,String workflowId,Map<String,Object> payload) {
    public ToolRequest { payload=payload==null?Map.of():Map.copyOf(payload); }
}
