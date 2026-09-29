// 智能助手模块：定义请求数据及其边界。
package com.fandazi.feature.agent;

import java.util.Map;

/**
 * Agent 网关内部请求。
 *
 * <p>保留旧字段访问方式供网关和历史测试使用；HTTP 客户端会将它编码为
 * `agent-protocol-v1` 规定的 schemaVersion、query 和 context 字段。</p>
 */
public record AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId) {
    /** 返回协议版本，集中避免客户端散落硬编码版本字符串。 */
    public String schemaVersion() { return "1.0"; }

    /** 返回由 Spring 生成的本次运行标识。 */
    public String runId() { return "run-" + traceId; }

    /** 返回传递给独立 Agent 的可信业务上下文。 */
    public Map<String, Object> context() { return Map.of("mode", mode == null ? "recommend" : mode); }
}
