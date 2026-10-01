// 智能助手模块：消费独立 Agent 服务的 V1 NDJSON 运行协议。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通过短期内部 JWT 调用独立 Agent，并逐行消费其 NDJSON 事件。
 * 每一行先写入 Spring 事件审计，再参与终态组装；网络中断统一转换成可重试失败。
 */
@Component
class HttpAgentServiceClient implements AgentServiceClient {
    private static final Logger log = LoggerFactory.getLogger(HttpAgentServiceClient.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final RestClient client;
    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final AgentMessageService messages;
    private final AgentProtocolValidator protocol;

    /** 初始化 HTTP 客户端、协议解析器和事件记录器。 */
    HttpAgentServiceClient(@Value("${agent.service.base-url:http://127.0.0.1:8090}") String baseUrl,
                           @Value("${agent.internal-token:}") String internalToken,
                           @Value("${agent.service.timeout-millis:5000}") long timeoutMillis,
                           ObjectMapper mapper, AgentMessageService messages) {
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.messages = messages;
        this.protocol = new AgentProtocolValidator(mapper);
        this.client = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofMillis(timeoutMillis));
                    setReadTimeout(Duration.ofMillis(timeoutMillis));
                }}).build();
    }

    /** 调用 V1 运行接口，并将完整事件流转换为兼容旧网关的终态结果。 */
    @Override
    public AgentResult run(AgentRunRequest request) {
        try {
            log.info("agent v1 request start: baseUrl={}, sessionId={}, mode={}", baseUrl, request.sessionId(), request.mode());
            return client.post().uri("/internal/v1/agent/runs")
                    .header("Authorization", "Bearer " + createInternalJwt(request))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("application/x-ndjson"))
                    .body(requestBody(request))
                    .exchange((httpRequest, response) -> consumeStream(response, request));
        } catch (AgentServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException | IOException exception) {
            log.error("agent v1 transport failure: baseUrl={}, sessionId={}, type={}, message={}",
                    baseUrl, request.sessionId(), exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试", exception);
        } catch (Exception exception) {
            log.error("agent v1 protocol failure: sessionId={}, type={}, message={}",
                    request.sessionId(), exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentServiceUnavailableException("Agent 服务返回格式无效，请稍后重试", exception);
        }
    }

    /** 构造协议 V1 请求，避免把旧版字段名直接暴露给独立服务。 */
    private Map<String, Object> requestBody(AgentRunRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", request.schemaVersion());
        body.put("userId", request.userId());
        body.put("sessionId", request.sessionId());
        body.put("traceId", request.traceId());
        body.put("runId", request.runId());
        body.put("query", request.query());
        body.put("context", request.context());
        protocol.validateRequest(body);
        return body;
    }

    /** 逐行解析 NDJSON；只有收到合法终态才返回，不能把半截流误报成成功。 */
    private AgentResult consumeStream(org.springframework.http.client.ClientHttpResponse response,
                                       AgentRunRequest request) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AgentServiceUnavailableException("Agent 服务返回 HTTP " + response.getStatusCode().value());
        }
        List<AgentResult.Event> events = new ArrayList<>();
        List<Map<String, Object>> streamedCards = new ArrayList<>();
        long previousSequence = -1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> event = mapper.readValue(line, JSON_OBJECT);
                // 在审计和展示前核对版本、链路和序号，拒绝其他任务事件或非法暂停终态。
                previousSequence = validateEvent(event, request, previousSequence);
                // 使用公开审计前重查后的事实事件，实时 SSE、历史重放和最终响应保持同一金额与资源。
                event = messages.record(request, event);
                events.add(event(event));
                String eventType = String.valueOf(event.getOrDefault("eventType", ""));
                if ("card".equals(eventType)) {
                    // 卡片可能先于暂停/完成到达，必须保留到公开响应，而非只读取最后一行。
                    Map<String, Object> payload = map(event.get("payload"));
                    Map<String, Object> card = map(payload.get("card"));
                    if (card.isEmpty()) throw new AgentServiceUnavailableException("卡片事件缺少公开卡片");
                    streamedCards.add(card);
                }
                if (java.util.Set.of("run_completed", "error", "input_required", "confirmation_required").contains(eventType)) {
                    return result(event, events, streamedCards);
                }
            }
        }
        throw new AgentServiceUnavailableException("Agent 服务连接中断，未收到运行终态");
    }

    /** 校验 1.1 事件及其任务边界；等待事件结束请求但不完成任务。 */
    private long validateEvent(Map<String, Object> raw, AgentRunRequest request, long previousSequence) {
        // 先执行权威嵌套结构，再校验单次请求才能判断的跨事件关联；两者职责不同。
        protocol.validateEvent(raw);
        Object sequence = raw.get("sequence");
        boolean validSequence = sequence instanceof Number number && number.doubleValue() == number.longValue()
                && number.longValue() >= 0 && number.longValue() > previousSequence;
        if (!request.schemaVersion().equals(raw.get("schemaVersion"))
                || !request.traceId().equals(raw.get("traceId")) || !request.runId().equals(raw.get("runId"))
                || string(raw.get("eventId")) == null || string(raw.get("eventId")).isBlank()
                || !validSequence) {
            throw new AgentServiceUnavailableException("Agent 事件版本、链路或结构不合法");
        }
        return ((Number) sequence).longValue();
    }

    /** 将公开协议事件映射为现有 SSE 网关使用的事件结构。 */
    private AgentResult.Event event(Map<String, Object> raw) {
        Map<String, Object> payload = map(raw.get("payload"));
        return new AgentResult.Event(String.valueOf(raw.getOrDefault("eventType", "progress")),
                AgentContractCompatibility.summary(payload),
                string(payload.get("toolName")), String.valueOf(payload.getOrDefault("stepStatus", raw.get("status"))), payload);
    }

    /** 将终态事件转换成旧网关结果，保证 Android 暂不需要理解内部协议细节。 */
    private AgentResult result(Map<String, Object> raw, List<AgentResult.Event> events, List<Map<String, Object>> streamedCards) {
        Map<String, Object> payload = map(raw.get("payload"));
        String status = String.valueOf(raw.getOrDefault("status", "failed"));
        AgentResult.Status mapped = statusFromV1(status);
        List<Map<String, Object>> cards = new ArrayList<>(streamedCards);
        // 暂停候选与先到卡片合并，公开确认摘要另走集中兼容映射，绝不下发内部凭证。
        if (payload.get("candidates") instanceof List<?> candidates) {
            for (Object value : candidates) {
                Map<String, Object> card = map(value);
                if (!cards.contains(card)) cards.add(card);
            }
        }
        Map<String, Object> pauseMetadata = AgentContractCompatibility.publicSummary(payload);
        if (payload.containsKey("inputKey") || payload.containsKey("question")) {
            // 补槽暂停没有嵌套 summary，保留顶层公共元数据才能让最终 DTO 与 SSE 使用相同槽位。
            // 复用递归脱敏规则，不因复制整个暂停载荷而把未来兼容字段中的内部凭证带入公开摘要。
            pauseMetadata = AgentContractCompatibility.publicEventPayload(payload);
            // 候选已合并到 cards，摘要不重复携带；确认暂停仍保留既有资源别名和摘要结构。
            pauseMetadata.remove("candidates");
        }
        return new AgentResult(mapped, AgentContractCompatibility.summary(payload),
                AgentContractCompatibility.nextAction(mapped, payload),
                pauseMetadata, events, cards,
                mapped == AgentResult.Status.FAILED ? string(payload.get("code")) : null, true);
    }

    /** 将 V1 协议终态映射为网关状态；等待补充输入不能被误判成失败。 */
    static AgentResult.Status statusFromV1(String status) {
        return switch (status) {
            case "succeeded" -> AgentResult.Status.COMPLETED;
            case "waiting_input" -> AgentResult.Status.NEEDS_INPUT;
            case "waiting_confirmation" -> AgentResult.Status.AWAITING_CONFIRMATION;
            case "cancelled" -> AgentResult.Status.CANCELLED;
            default -> AgentResult.Status.FAILED;
        };
    }

    /** 将协议中的 JSON 对象安全转换为字符串键 Map。 */
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /** 保留 JSON 空值，不把缺失协议字段拼接成字符串 null。 */
    private String string(Object value) { return value == null ? null : String.valueOf(value); }

    /** 使用配置中的内部共享密钥生成仅一分钟有效的 HS256 JWT。 */
    private String createInternalJwt(AgentRunRequest request) throws Exception {
        // 内外两个调用方向共享环境密钥，HS256 至少需要 32 字节；短开发凭据不得进入新业务路径。
        if (internalToken == null || internalToken.getBytes(StandardCharsets.UTF_8).length < 32) throw new AgentServiceUnavailableException("Agent 内部鉴权配置不足32字节");
        String header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
        String payload = encodeJson(Map.of("sub", request.userId(), "exp", Instant.now().getEpochSecond() + 60));
        String input = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(internalToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    /** 编码 JWT 的 JSON 片段，统一使用 UTF-8 和无填充 Base64URL。 */
    private String encodeJson(Map<String, Object> value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value));
    }
}
