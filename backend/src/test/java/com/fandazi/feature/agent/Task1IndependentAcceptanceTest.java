// Task1 独立验收：亲自使用生产 HTTP 客户端与公开事件转换核查合法空值、凭证隔离和取消。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** 仅增加验收测试，不修改生产实现；每次调用用临时端口和随机内部测试凭据。 */
class Task1IndependentAcceptanceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successfulToolResultWithNullErrorMustReachCompletion() throws Exception {
        // 合法成功工具事件必须带 error=null；审计转换不得把它误判为协议失败。
        AgentResult result = invoke(request -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", "query_faq");
            payload.put("ok", true);
            payload.put("data", Map.of("count", 1));
            payload.put("error", null);
            return List.of(event(request, "tool_result", "running", 1, payload),
                    event(request, "run_completed", "succeeded", 2, Map.of("message", "查询完成")));
        }, mock(AgentEventService.class));
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.events()).hasSize(2);
    }

    @Test
    void auditFailureDoesNotChangeBusinessCompletion() throws Exception {
        // 模拟数据库审计故障，确认合法业务终态仍返回，而不是触发退款或支付重试。
        AgentEventService audit = mock(AgentEventService.class);
        doThrow(new IllegalStateException("验收模拟审计故障"))
                .when(audit).record(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), any());
        AgentResult result = invoke(request -> List.of(
                event(request, "run_completed", "succeeded", 1, Map.of("message", "业务完成"))), audit);
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirmationGrantId", "confirmationGrant", "grantId", "token"})
    void privateGrantCannotEnterPublicToolResult(String field) {
        // 对四种敏感字段实际构造公开载荷负例；不能只检查 YAML 中出现了禁止字段名。
        assertThatThrownBy(() -> invoke(request -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", "prepare_refund");
            payload.put("ok", true);
            payload.put("data", Map.of(field, "acceptance-only-reference"));
            payload.put("error", null);
            return List.of(event(request, "tool_result", "running", 1, payload));
        }, mock(AgentEventService.class))).isInstanceOf(AgentServiceUnavailableException.class)
                .hasMessageContaining("结构");
    }

    @Test
    void cancellationRemainsNormalTerminalWithPublicCompatibility() throws Exception {
        // 取消在内部保留 CANCELLED；旧 Android 的 COMPLETED 兼容必须同时保留 cancelled 动作。
        AgentResult result = invoke(request -> List.of(
                event(request, "run_completed", "cancelled", 1, Map.of("message", "已取消"))),
                mock(AgentEventService.class));
        assertThat(result.status()).isEqualTo(AgentResult.Status.CANCELLED);
        assertThat(result.nextAction()).isEqualTo("cancelled");
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void conflictingCanonicalAndLegacyGrantMustBeRejected() {
        // 新旧确认字段不同表示错绑风险，兼容适配器必须拒绝，不能静默选择其中一个。
        assertThatThrownBy(() -> AgentContractCompatibility.toolPayload("request_refund", Map.of(
                "confirmationGrantId", "acceptance-reference-a", "confirmationGrant", "acceptance-reference-b")))
                .isInstanceOf(com.fandazi.feature.DomainException.class);
    }

    @Test
    void streamWithoutPauseOrTerminalMustFail() {
        // HTTP 200 与进度事件不等于业务成功；连接提前结束应产生明确的可重试失败。
        assertThatThrownBy(() -> invoke(request -> List.of(event(request, "progress", "running", 1,
                Map.of("stepId", "lookup", "label", "正在查询", "stepStatus", "running"))),
                mock(AgentEventService.class))).isInstanceOf(AgentServiceUnavailableException.class)
                .hasMessageContaining("中断");
    }

    /** 构造本轮身份一致的权威事件；序号只在本次 traceId 内递增。 */
    private Map<String, Object> event(AgentRunRequest request, String type, String status, int sequence,
                                      Map<String, Object> payload) {
        return Map.of("schemaVersion", "1.1", "eventId", UUID.randomUUID().toString(), "eventType", type,
                "traceId", request.traceId(), "runId", request.runId(), "sequence", sequence,
                "status", status, "payload", payload);
    }

    /** 启动临时真实 HTTP 服务并执行生产客户端；无线上模型、真实凭据或生产数据库访问。 */
    private AgentResult invoke(Function<AgentRunRequest, List<Map<String, Object>>> factory,
                               AgentEventService audit) throws Exception {
        AgentRunRequest request = new AgentRunRequest("42", "acceptance-session", "查询帮助", "recommend",
                UUID.randomUUID().toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/agent/runs", exchange -> {
            exchange.getRequestBody().readAllBytes();
            StringBuilder stream = new StringBuilder();
            for (Map<String, Object> item : factory.apply(request)) {
                stream.append(mapper.writeValueAsString(item)).append('\n');
            }
            byte[] bytes = stream.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            HttpAgentServiceClient client = new HttpAgentServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    UUID.randomUUID().toString(), 2000, mapper, new AgentMessageService(audit));
            return client.run(request);
        } finally {
            server.stop(0);
        }
    }
}
