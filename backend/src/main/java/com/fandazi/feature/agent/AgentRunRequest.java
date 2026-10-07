// 智能助手模块：定义请求数据及其边界。
package com.fandazi.feature.agent;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Agent 网关内部请求。
 *
 * <p>保留旧字段访问方式供网关和历史测试使用；HTTP 客户端会将它编码为
 * `agent-protocol-v1` 规定的 schemaVersion、query 和 context 字段。</p>
 */
public record AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId,
                              String runId, Map<String, Object> context) {
    /** 创建可信请求；任务标识由 Spring 提供，上下文复制后不允许外部修改。 */
    public AgentRunRequest {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("任务标识不能为空");
        Map<String, Object> trusted = new LinkedHashMap<>();
        if (context != null) trusted.putAll(context);
        trusted.put("mode", mode == null ? "recommend" : mode);
        context = java.util.Collections.unmodifiableMap(trusted);
    }

    /** 兼容历史测试与单次请求调用方；真实网关使用会话中持久化的任务标识。 */
    public AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId) {
        this(userId, sessionId, text, mode, traceId, UUID.randomUUID().toString(), Map.of());
    }

    /** 返回协议版本，集中避免客户端散落硬编码版本字符串。 */
    public String schemaVersion() { return "1.1"; }

    /** 权威输入名称为 query；旧 text 访问器仅供既有网关内部兼容。 */
    public String query() { return text; }
}
