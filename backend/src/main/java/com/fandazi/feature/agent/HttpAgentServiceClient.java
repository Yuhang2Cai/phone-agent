package com.fandazi.feature.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class HttpAgentServiceClient implements AgentServiceClient {
    private final RestClient client;

    HttpAgentServiceClient(@Value("${agent.service.base-url:http://127.0.0.1:8090}") String baseUrl,
                           @Value("${agent.service.timeout-millis:5000}") long timeoutMillis) {
        this.client = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofMillis(timeoutMillis)); setReadTimeout(Duration.ofMillis(timeoutMillis));
                }}).build();
    }

    @Override public AgentResult run(AgentRunRequest request) {
        try {
            Map<?, ?> response = client.post().uri("/internal/agent/run").contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(Map.class);
            if (response == null) throw new AgentServiceUnavailableException("Agent 服务返回为空，请稍后重试");
            return result(response);
        } catch (AgentServiceUnavailableException exception) { throw exception;
        } catch (RestClientException exception) {
            throw new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试", exception);
        }
    }
    private AgentResult result(Map<?, ?> body) {
        String status = String.valueOf(orDefault(body, "status", "failed")).toUpperCase();
        AgentResult.Status value;
        try { value = AgentResult.Status.valueOf(status); } catch (IllegalArgumentException ignored) { value = AgentResult.Status.FAILED; }
        Map<String, Object> cart = asMap(body.get("candidate_cart"));
        List<AgentResult.Event> events = asList(body.get("events")).stream().map(this::event).toList();
        Object error = body.get("error");
        return new AgentResult(value, String.valueOf(orDefault(body, "message", "请求未完成。")), String.valueOf(orDefault(body, "next_action", "retry")), cart, events, error == null ? null : String.valueOf(error));
    }
    private AgentResult.Event event(Object value) { Map<String,Object> event=asMap(value); return new AgentResult.Event(String.valueOf(event.getOrDefault("kind", "info")),String.valueOf(event.getOrDefault("message", "Agent 事件")),string(event.get("tool_name")),string(event.getOrDefault("kind", "info")),asMap(event.get("data"))); }
    @SuppressWarnings("unchecked") private Map<String,Object> asMap(Object value) { return value instanceof Map<?,?> raw ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->String.valueOf(e.getKey()),Map.Entry::getValue,(a,b)->b,java.util.LinkedHashMap::new)) : Map.of(); }
    private List<?> asList(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private Object orDefault(Map<?, ?> value, String key, Object fallback) { Object found=value.get(key); return found == null ? fallback : found; }
}
