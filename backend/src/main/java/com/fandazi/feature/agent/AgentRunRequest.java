// 智能助手模块：定义请求数据及其边界。
package com.fandazi.feature.agent;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Agent 网关内部请求。
 *
 * <p>保留旧字段访问方式供网关和历史测试使用；HTTP 客户端会将它编码为
 * 配置选定版本规定的 schemaVersion、query 和 context 字段。历史 schemaVersion()
 * 访问器只用于旧调用方，HTTP 编码必须以服务配置和已固定的活动任务版本为准。</p>
 */
public record AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId,
                              String runId, Map<String, Object> context, String transportVersion) {
    /** 创建可信请求；任务标识由 Spring 提供，上下文复制后不允许外部修改。 */
    public AgentRunRequest {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("任务标识不能为空");
        Map<String, Object> trusted = new LinkedHashMap<>();
        if (context != null) trusted.putAll(context);
        trusted.put("mode", mode == null ? "recommend" : mode);
        context = java.util.Collections.unmodifiableMap(trusted);
        // 2.2/2.3只由显式任务网关固定，旧/messages和部署默认版本仍保持原协商路径。
        if(transportVersion!=null && !java.util.Set.of("1.1","2.0","2.1","2.2","2.3").contains(transportVersion))
            throw new IllegalArgumentException("Agent 协议版本不受支持");
    }

    /** 兼容原网关构造器；未固定版本时由受控 HTTP 配置选择，用户不能设置版本。 */
    public AgentRunRequest(String userId,String sessionId,String text,String mode,String traceId,String runId,Map<String,Object> context) {
        this(userId,sessionId,text,mode,traceId,runId,context,null);
    }

    /** 兼容历史测试与单次请求调用方；真实网关使用会话中持久化的任务标识。 */
    public AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId) {
        this(userId, sessionId, text, mode, traceId, UUID.randomUUID().toString(), Map.of());
    }

    /** 历史兼容访问器，不是 HTTP 线上版本来源；新代码读取 transportVersion 和受控配置。 */
    public String schemaVersion() { return "1.1"; }

    /** 权威输入名称为 query；旧 text 访问器仅供既有网关内部兼容。 */
    public String query() { return text; }
}
