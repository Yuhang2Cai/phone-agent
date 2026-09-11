package com.fandazi.feature.agent;

public record AgentRunRequest(String userId, String sessionId, String text, String mode, String traceId) {}
