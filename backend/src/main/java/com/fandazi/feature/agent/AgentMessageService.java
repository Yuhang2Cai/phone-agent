// Agent 运行消息模块：把独立服务返回的公开事件接入 Spring 会话审计。
package com.fandazi.feature.agent;

import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统一处理跨服务 Agent 事件。
 *
 * <p>独立服务只返回公开业务摘要，Spring 在收到每一行 NDJSON 后立即记录，
 * 这样客户端断开或后续终态解析失败时仍能保留已经发生的进度。</p>
 */
@Service
public class AgentMessageService {
    private final AgentEventService events;

    /** 注入已有事件持久化服务，避免重复建立一套消息写入事务。 */
    public AgentMessageService(AgentEventService events) { this.events = events; }

    /**
     * 记录一条独立 Agent 事件。
     *
     * @param request 当前 Spring 网关请求
     * @param event V1 协议事件
     */
    public void record(AgentRunRequest request, Map<String, Object> event) {
        Long userId = parseUserId(request.userId());
        if (userId == null) return;
        String type = text(event.get("eventType"), "progress");
        String summary = text(payload(event).get("message"), "Agent 运行进度已更新");
        String toolName = textOrNull(payload(event).get("toolName"));
        String status = text(event.get("status"), "running");
        events.record(userId, request.sessionId(), type, summary, toolName, status, payload(event));
    }

    /** 提取协议 payload，避免把顶层链路字段重复写入业务摘要。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> event) {
        Object value = event.get("payload");
        return value instanceof Map<?, ?> raw ? raw.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(item -> String.valueOf(item.getKey()), Map.Entry::getValue,
                        (left, right) -> right, java.util.LinkedHashMap::new)) : Map.of();
    }

    /** 只接受数字用户标识，拒绝把公开字符串误当成数据库主键。 */
    private Long parseUserId(String value) { try { return Long.valueOf(value); } catch (RuntimeException ignored) { return null; } }
    private String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private String textOrNull(Object value) { return value == null ? null : String.valueOf(value); }
}
