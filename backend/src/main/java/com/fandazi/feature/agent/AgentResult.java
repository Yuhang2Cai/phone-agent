// 智能助手模块：定义该模块的数据与行为。
package com.fandazi.feature.agent;

import java.util.List;
import java.util.Map;

public record AgentResult(Status status, String message, String nextAction, Map<String, Object> candidateCart,
                          List<Event> events, List<Map<String, Object>> cards, String error, boolean eventsRecorded) {
    /** 网关内部状态；取消保留独立语义，公开旧 DTO 暂映射为已结束以兼容现有 Android。 */
    public enum Status {
        COMPLETED, NEEDS_INPUT, AWAITING_CONFIRMATION, FAILED, CANCELLED;

        /** 兼容旧客户端状态集合；nextAction=cancelled 仍区分用户取消与业务成功。 */
        @com.fasterxml.jackson.annotation.JsonValue
        public String publicValue() { return this == CANCELLED ? "COMPLETED" : name(); }
    }
    public record Event(String type, String summary, String toolName, String status, Map<String, Object> payload) {}

    /** 兼容旧测试替身和旧业务代码，默认认为事件尚未由客户端写入审计。 */
    public AgentResult(Status status, String message, String nextAction, Map<String, Object> candidateCart,
                       List<Event> events, String error) {
        this(status, message, nextAction, candidateCart, events, List.of(), error, false);
    }

    /** 兼容旧调用方：没有卡片时显式使用空列表。 */
    public AgentResult(Status status, String message, String nextAction, Map<String, Object> candidateCart,
                       List<Event> events, String error, boolean eventsRecorded) {
        this(status, message, nextAction, candidateCart, events, List.of(), error, eventsRecorded);
    }
// 处理 `completed` 相关逻辑。
    public static AgentResult completed(String message, String nextAction, List<Event> events) {
        return new AgentResult(Status.COMPLETED, message, nextAction, Map.of(), events, List.of(), null, false);
    }
// 处理 `failed` 相关逻辑。
    public static AgentResult failed(String message) {
        return new AgentResult(Status.FAILED, "请求未完成。", "retry", Map.of(), List.of(), List.of(), message, false);
    }
}
