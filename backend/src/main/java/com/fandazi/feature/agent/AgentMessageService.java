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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentMessageService.class);
    private final AgentEventService events;
    private final AgentCardEnrichmentService cards;

    /** 注入已有事件持久化服务，避免重复建立一套消息写入事务。 */
    public AgentMessageService(AgentEventService events) { this.events = events; this.cards = null; }

    /** 生产路径在实时审计前重查资源和确认金额；历史纯协议测试构造器不依赖业务数据库。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentMessageService(AgentEventService events, AgentCardEnrichmentService cards) { this.events = events; this.cards = cards; }

    /**
     * 记录一条独立 Agent 事件。
     *
     * @param request 当前 Spring 网关请求
     * @param event V1 协议事件
     */
    public Map<String, Object> record(AgentRunRequest request, Map<String, Object> event) {
        Long userId = parseUserId(request.userId());
        if (userId == null) return event;
        // 数据规范化必须先于实时 SSE 发布，最终网关才重查会使伪造金额已进入事件历史。
        Map<String, Object> normalized = new java.util.LinkedHashMap<>(event);
        Map<String, Object> publicPayload = payload(event);
        if (cards != null) {
            if (publicPayload.get("summary") instanceof Map<?, ?> source) {
                Map<String, Object> summary = new java.util.LinkedHashMap<>(); source.forEach((key, value) -> summary.put(String.valueOf(key), value));
                publicPayload.put("summary", cards.enrichSummary(userId, request.sessionId(), summary));
            }
            if (publicPayload.get("card") instanceof Map<?, ?> source) {
                Map<String, Object> card = new java.util.LinkedHashMap<>(); source.forEach((key, value) -> card.put(String.valueOf(key), value));
                publicPayload.put("card", cards.enrich(userId, request.sessionId(), card));
            }
            if (publicPayload.get("candidates") instanceof java.util.List<?> candidates) {
                publicPayload.put("candidates", candidates.stream().map(value -> {
                    Map<String, Object> card = new java.util.LinkedHashMap<>();
                    if (value instanceof Map<?, ?> source) source.forEach((key, field) -> card.put(String.valueOf(key), field));
                    return cards.enrich(userId, request.sessionId(), card);
                }).toList());
            }
        }
        // 原事件身份供 HTTP/SSE 双交付去重，数据库事件主键仍独立作为 Last-Event-ID 游标。
        // 从已校验的顶层复制，不使用业务 payload 中可能存在的同名值覆盖权威链路。
        // 2.2的taskContext是已校验顶层栅栏元数据，端上按task/代次过滤迟到事件；不能丢弃只剩run标识。
        for (String key : java.util.List.of("schemaVersion", "eventId", "sequence", "runId", "traceId", "status", "taskContext")) {
            if (event.get(key) != null) publicPayload.put(key, event.get(key));
        }
        normalized.put("payload", publicPayload);
        event = normalized;
        String type = text(event.get("eventType"), "progress");
        String summary = AgentContractCompatibility.summary(payload(event));
        String toolName = textOrNull(payload(event).get("toolName"));
        // 任务状态与步骤状态分别保存；步骤完成不意味着整个任务已经结束。
        String status = text(event.get("status"), "running");
        // 公开进度携带任务标识供端上去重；审计故障不能把正常暂停或业务成功改为失败。
        Map<String, Object> content = new java.util.LinkedHashMap<>(payload(event));
        content.putIfAbsent("runId", request.runId());
        content.putIfAbsent("traceId", request.traceId());
        // 知识交付许可和事件必须提交后才能让HTTP客户端组装成功结果。
        // 该调用在可忽略审计catch之外，事务提交失败也不能被吞掉后继续发送旧正文。
        if (events.requiresKnowledgeDelivery(userId,request.sessionId(),type,content)) {
            events.record(userId,request.sessionId(),type,summary,toolName,status,content);
            return normalized;
        }
        try {
            events.record(userId, request.sessionId(), type, summary, toolName, status, content);
        } catch (RuntimeException exception) {
            log.error("Agent 事件审计失败 runId={} traceId={} eventType={}", request.runId(), request.traceId(), type, exception);
        }
        // HTTP 客户端使用同一份事实结果组装返回事件，避免审计已正确但最终响应仍带模型伪金额。
        return normalized;
    }

    /** 提取协议 payload，避免把顶层链路字段重复写入业务摘要。 */
    private Map<String, Object> payload(Map<String, Object> event) {
        Object value = event.get("payload");
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        // 成功工具事件合法包含 error=null；Collectors.toMap 的非空限制不适合 JSON 对象。
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /** 只接受数字用户标识，拒绝把公开字符串误当成数据库主键。 */
    private Long parseUserId(String value) { try { return Long.valueOf(value); } catch (RuntimeException ignored) { return null; } }
    private String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private String textOrNull(Object value) { return value == null ? null : String.valueOf(value); }
}
