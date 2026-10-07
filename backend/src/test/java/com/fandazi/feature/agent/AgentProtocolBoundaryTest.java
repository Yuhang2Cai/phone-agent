// Agent 协议边界回归：使用真实 HTTP/NDJSON 传输校验暂停、身份、版本与卡片组装。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 使用临时本地服务发送可控的公开事件；不调用模型、数据库或生产网络。 */
class AgentProtocolBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publicHttpAndSsePreserveCanonicalIdentityAndRunStatus() throws Exception {
        // HTTP 与实时审计使用同一原事件身份；步骤完成不能覆盖任务仍运行的状态。
        String identity = UUID.randomUUID().toString();
        AgentResult result = invoke(request -> {
            Map<String, Object> progress = new java.util.LinkedHashMap<>(event(request, "progress", "running", 1,
                    Map.of("stepId", "lookup", "label", "查询已完成", "stepStatus", "succeeded")));
            progress.put("eventId", identity);
            return List.of(progress, event(request, "run_completed", "succeeded", 2, Map.of("message", "完成")));
        });
        assertThat(result.events().get(0).status()).isEqualTo("running");
        assertThat(result.events().get(0).payload()).containsEntry("eventId", identity)
                .containsEntry("sequence", 1).containsEntry("stepStatus", "succeeded");
        assertThat(result.events().get(0).payload().get("runId")).isNotNull();
        assertThat(result.events().get(0).payload().get("traceId")).isNotNull();
    }

    @Test
    void inputRequiredEndsRequestWithoutReportingInterruptedStream() throws Exception {
        // 等待补充输入合法结束本轮 HTTP 流，任务本身仍暂停而非失败。
        AgentResult result = invoke(request -> List.of(event(request, "input_required", "waiting_input", 1,
                input("请选择团购券"))));
        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_INPUT);
        assertThat(result.message()).isEqualTo("请选择团购券");
    }

    @Test
    void inputRequiredRetainsStructuredSlotMetadataInFinalResult() throws Exception {
        // 退款原因和投诉标题、正文都没有嵌套 summary；最终 DTO 必须保留槽位，不能只在 SSE 中可见。
        for (Map<String, String> slot : List.of(
                Map.of("inputKey", "reason", "question", "请补充退款原因"),
                Map.of("inputKey", "subject", "question", "请补充投诉标题"),
                Map.of("inputKey", "description", "question", "请补充投诉正文"))) {
            // 从真实 NDJSON 边界调用生产客户端，预期字段直接来自手工场景，不复用待修复的映射函数。
            AgentResult result = invoke(request -> List.of(event(request, "input_required", "waiting_input", 1,
                    Map.of("question", slot.get("question"), "inputKey", slot.get("inputKey"), "candidates", List.of()))));
            assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_INPUT);
            assertThat(result.nextAction()).isEqualTo("input_required");
            assertThat(result.candidateCart()).containsEntry("question", slot.get("question"))
                    .containsEntry("inputKey", slot.get("inputKey"));
            // 候选已有专门的 cards 出口；摘要只保留公共补槽元数据，避免复制一份候选引起重复渲染。
            assertThat(result.candidateCart()).doesNotContainKey("candidates");
            assertThat(result.cards()).isEmpty();
        }
    }

    @Test
    void confirmationRequiredEndsRequestWithoutFakeRunCompletion() throws Exception {
        // 等待确认必须允许结束传输，不能要求服务再发送虚假的 run_completed。
        AgentResult result = invoke(request -> List.of(event(request, "confirmation_required", "waiting_confirmation", 1,
                confirmation())));
        assertThat(result.status()).isEqualTo(AgentResult.Status.AWAITING_CONFIRMATION);
    }

    @Test
    void pausedStatusCannotBeEncodedAsRunCompleted() {
        // run_completed 只能完成或取消任务；拒绝模型把暂停伪装为终态。
        assertThatThrownBy(() -> invoke(request -> List.of(event(request, "run_completed", "waiting_input", 1, Map.of()))))
                .isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test
    void eventFromAnotherRunIsRejectedBeforeDelivery() {
        // 即使 JSON 完整，其他任务的事件也不能进入当前用户页面和审计。
        assertThatThrownBy(() -> invoke(request -> {
            Map<String, Object> raw = new java.util.LinkedHashMap<>(event(request, "run_completed", "succeeded", 1, Map.of()));
            raw.put("runId", UUID.randomUUID().toString());
            return List.of(raw);
        })).isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test
    void mismatchedProtocolVersionIsRejected() {
        // 不在权威解析器里静默接受旧版本；兼容应由明确的适配边界负责。
        assertThatThrownBy(() -> invoke(request -> {
            Map<String, Object> raw = new java.util.LinkedHashMap<>(event(request, "run_completed", "succeeded", 1, Map.of()));
            raw.put("schemaVersion", "1.0");
            return List.of(raw);
        })).isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test
    void sequenceMustIncreaseWithinOneResponse() {
        // 重复序号会导致重复进度或卡片，必须拒绝而不是偷偷覆盖审计结果。
        assertThatThrownBy(() -> invoke(request -> List.of(
                event(request, "progress", "running", 1, Map.of("stepId", "lookup", "label", "正在查询", "stepStatus", "running")),
                event(request, "run_completed", "succeeded", 1, Map.of("message", "完成")))))
                .isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test
    void cardsFromStreamingEventsAreKeptInFinalResult() throws Exception {
        // 卡片可以早于终态到达；最终事件不重复携带卡片时，网关不能丢失已收到的结果。
        AgentResult result = invoke(request -> List.of(
                event(request, "card", "running", 1, Map.of("card", Map.of(
                        "schemaVersion", "1.1", "cardType", "voucher", "resourceId", "fixture-voucher",
                        "data", Map.of("voucherId", "fixture-voucher", "orderId", "fixture-order", "dealTitle", "测试套餐",
                                "storeName", "测试门店", "status", "UNUSED", "canRefund", true, "expiresAt", "2030-01-01T00:00:00Z"),
                        "actions", List.of(action("select_voucher"))))),
                event(request, "input_required", "waiting_input", 2, input("请选择团购券"))));
        assertThat(result.cards()).hasSize(1);
        assertThat(result.cards().get(0)).containsEntry("cardType", "voucher");
    }

    @Test
    void invalidPausePayloadIsRejected() {
        // 顶层状态正确仍不够；没有 inputKey/candidates 的追问不能进入真实页面。
        assertThatThrownBy(() -> invoke(request -> List.of(event(request, "input_required", "waiting_input", 1,
                Map.of("question", "请选择"))))).isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test
    void confirmationWithNegativeAmountIsRejected() {
        // 直接执行权威 JSON Schema 的金额和动作边界，防止只有字符串字段名测试通过。
        assertThatThrownBy(() -> invoke(request -> {
            Map<String, Object> payload = new java.util.LinkedHashMap<>(confirmation());
            Map<String, Object> summary = mapper.convertValue(payload.get("summary"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            summary.put("amountCents", -1);
            payload.put("summary", summary);
            return List.of(event(request, "confirmation_required", "waiting_confirmation", 1, payload));
        })).isInstanceOf(AgentServiceUnavailableException.class);
    }

    /** 追问始终使用权威载荷，而不是以旧 message/nextAction 假装满足新协议。 */
    private Map<String, Object> input(String question) {
        return Map.of("question", question, "inputKey", "voucherId", "candidates", List.of());
    }

    /** 构造公开确认摘要，刻意不包含内部确认凭证。 */
    private Map<String, Object> confirmation() {
        return Map.of("summary", Map.of("action", "request_refund", "resourceId", "fixture-voucher",
                "title", "请确认退款", "amountCents", 100, "ruleVersion", "refund-v1",
                "dealTitle", "隔离测试套餐", "reason", "计划改变"),
                "actions", List.of(action("request_refund")));
    }

    /** 公开卡片动作由服务端资格决定，测试只使用隔离资源引用。 */
    private Map<String, Object> action(String type) {
        return Map.of("actionId", "fixture-action", "actionType", type, "label", "操作", "resourceId", "fixture-voucher", "enabled", true);
    }

    /** 构造满足权威事件字段的测试对象，链路始终来自被测试的实际请求。 */
    private Map<String, Object> event(AgentRunRequest request, String type, String status, int sequence,
                                      Map<String, Object> payload) {
        return Map.of("schemaVersion", "1.1", "eventId", UUID.randomUUID().toString(),
                "eventType", type, "traceId", request.traceId(), "runId", request.runId(),
                "sequence", sequence, "status", status, "payload", payload);
    }

    /** 启动一次隔离的 HTTP 服务，实际调用生产客户端并在结束时释放端口。 */
    private AgentResult invoke(Function<AgentRunRequest, List<Map<String, Object>>> factory) throws Exception {
        AgentRunRequest request = new AgentRunRequest("42", "fixture-session", "查询团购券", "recommend", UUID.randomUUID().toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/agent/runs", exchange -> {
            // 校验真实请求体而非 mock 调用次数，防止权威 query 字段被旧 text 覆盖。
            Map<?, ?> body = mapper.readValue(exchange.getRequestBody(), Map.class);
            assertThat(body.containsKey("query")).isTrue();
            assertThat(body.containsKey("text")).isFalse();
            StringBuilder lines = new StringBuilder();
            for (Map<String, Object> raw : factory.apply(request)) lines.append(mapper.writeValueAsString(raw)).append('\n');
            byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            // 凭据仅为运行时随机测试值，不保存真实密钥或业务数据。
            HttpAgentServiceClient client = new HttpAgentServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    UUID.randomUUID().toString(), 2000, mapper, new AgentMessageService(mock(AgentEventService.class)));
            return client.run(request);
        } finally {
            server.stop(0);
        }
    }
}
