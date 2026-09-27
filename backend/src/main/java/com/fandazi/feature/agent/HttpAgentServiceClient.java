// 智能助手模块：封装远程服务通信。
package com.fandazi.feature.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
class HttpAgentServiceClient implements AgentServiceClient {
    private static final Logger log = LoggerFactory.getLogger(HttpAgentServiceClient.class);
    private final RestClient client;
    private final String baseUrl;
    private final String internalToken;

// 初始化对象依赖与默认状态。
    HttpAgentServiceClient(@Value("${agent.service.base-url:http://127.0.0.1:8090}") String baseUrl,
                           @Value("${agent.internal-token:}") String internalToken,
                           @Value("${agent.service.timeout-millis:5000}") long timeoutMillis) {
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
        this.client = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofMillis(timeoutMillis)); setReadTimeout(Duration.ofMillis(timeoutMillis));
                }}).build();
    }

// 运行当前数据。
    @Override public AgentResult run(AgentRunRequest request) {
        try {
            log.info("agent request start: baseUrl={}, sessionId={}, mode={}", baseUrl, request.sessionId(), request.mode());
            Map<?, ?> response = client.post().uri("/internal/agent/run")
                    .header("X-Agent-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(Map.class);
            if (response == null) throw new AgentServiceUnavailableException("Agent 服务返回为空，请稍后重试");
            log.info("agent request completed: sessionId={}, responseKeys={}", request.sessionId(), response.keySet());
            return result(response);
        } catch (AgentServiceUnavailableException exception) { throw exception;
        } catch (RestClientException exception) {
            log.error("agent request transport failure: baseUrl={}, sessionId={}, type={}, message={}", baseUrl, request.sessionId(), exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试", exception);
        } catch (RuntimeException exception) {
            log.error("agent response parse failure: sessionId={}, type={}, message={}", request.sessionId(), exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentServiceUnavailableException("Agent 服务返回格式无效，请稍后重试", exception);
        }
    }
// 处理 `result` 相关逻辑。
    private AgentResult result(Map<?, ?> body) {
        String status = String.valueOf(orDefault(body, "status", "failed")).toUpperCase();
        AgentResult.Status value;
        try { value = AgentResult.Status.valueOf(status); } catch (IllegalArgumentException ignored) { value = AgentResult.Status.FAILED; }
        Map<String, Object> cart = asMap(body.get("candidate_cart"));
        List<AgentResult.Event> events = asList(body.get("events")).stream().map(this::event).toList();
        Object error = body.get("error");
        return new AgentResult(value, String.valueOf(orDefault(body, "message", "请求未完成。")), String.valueOf(orDefault(body, "next_action", "retry")), cart, events, error == null ? null : String.valueOf(error));
    }
// 处理 `event` 相关逻辑。
    private AgentResult.Event event(Object value) { Map<String,Object> event=asMap(value); return new AgentResult.Event(String.valueOf(event.getOrDefault("kind", "info")),String.valueOf(event.getOrDefault("message", "Agent 事件")),string(event.get("tool_name")),string(event.getOrDefault("kind", "info")),asMap(event.get("data"))); }
// 处理 `asMap` 相关逻辑。
    private Map<String,Object> asMap(Object value) {
        if (!(value instanceof Map<?,?> raw)) return Map.of();
        Map<String,Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
// 处理 `asList` 相关逻辑。
    private List<?> asList(Object value) { return value instanceof List<?> list ? list : List.of(); }
// 处理 `string` 相关逻辑。
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
// 处理 `orDefault` 相关逻辑。
    private Object orDefault(Map<?, ?> value, String key, Object fallback) { Object found=value.get(key); return found == null ? fallback : found; }
}
