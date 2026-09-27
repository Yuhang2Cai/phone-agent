// 智能助手模块：定义该模块的数据与行为。
package com.fandazi.feature.agent;

import java.util.List;
import java.util.Map;

public record AgentResult(Status status, String message, String nextAction, Map<String, Object> candidateCart,
                          List<Event> events, String error) {
    public enum Status { COMPLETED, NEEDS_INPUT, AWAITING_CONFIRMATION, FAILED }
    public record Event(String type, String summary, String toolName, String status, Map<String, Object> payload) {}
    public static AgentResult completed(String message, String nextAction, List<Event> events) {
        return new AgentResult(Status.COMPLETED, message, nextAction, Map.of(), events, null);
    }
    public static AgentResult failed(String message) {
        return new AgentResult(Status.FAILED, "请求未完成。", "retry", Map.of(), List.of(), message);
    }
}
