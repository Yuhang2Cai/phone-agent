// 智能助手模块：定义请求数据及其边界。
package com.fandazi.feature.agent;

public record AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId) {}
