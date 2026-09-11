package com.fandazi.feature.agent;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service public class AgentGatewayService {
    private final AgentSessionService sessions; private final AgentServiceClient client; private final AgentEventService events;
    public AgentGatewayService(AgentSessionService sessions, AgentServiceClient client, AgentEventService events) { this.sessions=sessions; this.client=client; this.events=events; }
    public AgentResult handle(Long userId,String sessionId,String text,AgentMode mode) {
        String traceId=UUID.randomUUID().toString(); AgentSession session=sessions.start(userId,sessionId,mode,traceId);
        if (mode == AgentMode.CONFIRM && isConfirmation(text)) sessions.confirm(userId, sessionId);
        events.record(userId, sessionId, "parsed", "已接收点餐请求", null, "running", java.util.Map.of());
        try {
            AgentResult result=client.run(new AgentRunRequest(userId.toString(),sessionId,text,mode == AgentMode.THIS_TASK_AUTO ? "auto_order" : "order",traceId));
            for (AgentResult.Event event: result.events()) events.record(userId,sessionId,event.type(),event.summary(),event.toolName(),event.status(),event.payload());
            events.record(userId,sessionId,result.status() == AgentResult.Status.FAILED ? "failed" : "completed",result.message(),null,result.status().name().toLowerCase(),result.candidateCart());
            return result;
        } catch (AgentServiceUnavailableException exception) {
            events.record(userId,sessionId,"failed",exception.getMessage(),null,"retryable",java.util.Map.of()); return AgentResult.failed(exception.getMessage());
        }
    }
    private boolean isConfirmation(String text) { String normal=text == null ? "" : text.replace(" ", ""); return normal.equals("确认") || normal.equals("确认下单") || normal.equals("确定") || normal.equals("确定下单"); }
}
