// Task3 独立验收：直接调用生产 NDJSON 客户端，核对半截流、非法 JSON 和取消兼容边界。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 只模拟网络响应边界，客户端解析、终态判定和异常包装均使用生产代码；不连接真实用户库。 */
class Task3StreamInterruptionAcceptanceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void streamClosedAfterStartedMustFailInsteadOfSucceeding() throws Exception {
        // 服务可以先成功发送第一事件再断开；HTTP 200 不能被当作完整业务成功。
        AgentRunRequest request = request();
        String started = mapper.writeValueAsString(Map.of(
                "schemaVersion", "1.1", "eventType", "run_started", "eventId", UUID.randomUUID().toString(),
                "runId", request.runId(), "traceId", request.traceId(), "sequence", 0,
                "status", "running", "payload", Map.of())) + "\n";
        assertThatThrownBy(() -> consume(request, started))
                .isInstanceOf(AgentServiceUnavailableException.class).hasMessageContaining("中断");
    }

    @Test
    void truncatedJsonMustFailInsteadOfProducingBusinessResult() {
        // 网络截断 JSON 时不允许忽略坏行并构造成功；异常须由网关可识别的失败类型承接。
        assertThatThrownBy(() -> consume(request(), "{\"schemaVersion\":\"1.1\""))
                .isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test
    void legacyPublicDtoCancellationStillCarriesExplicitCancelledAction() throws Exception {
        // 旧 DTO 的 COMPLETED 只表示请求结束，明确 nextAction 和事件仍须保留取消语义。
        AgentResult value = new AgentResult(AgentResult.Status.CANCELLED, "已取消本次操作", "cancelled",
                Map.of(), List.of(new AgentResult.Event("run_completed", "已取消本次操作", null,
                "cancelled", Map.of("message", "已取消本次操作"))), List.of(), null, true);
        var json = mapper.readTree(mapper.writeValueAsBytes(value));
        assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(json.get("nextAction").asText()).isEqualTo("cancelled");
        assertThat(json.get("events").get(0).get("status").asText()).isEqualTo("cancelled");
    }

    /** 构造本用例独立的请求身份，资源和查询均为合成值，未使用真实账号或凭据。 */
    private AgentRunRequest request() {
        return new AgentRunRequest("42", "independent-stream-session", "你好", "confirm", UUID.randomUUID().toString());
    }

    /** 本机临时 HTTP 服务发送指定内容后关闭连接，确保测试走实际字节流解析入口。 */
    private AgentResult consume(AgentRunRequest request, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/agent/runs", exchange -> {
            // 读取请求体避免 HTTP 客户端尚未发送完成就关闭，失败只由待测响应内容触发。
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            HttpAgentServiceClient client = new HttpAgentServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    UUID.randomUUID().toString(), 2000, mapper, new AgentMessageService(mock(AgentEventService.class)));
            return client.run(request);
        } finally {
            // 临时服务属于测试自己，结束时只释放自己的监听端口。
            server.stop(0);
        }
    }
}
