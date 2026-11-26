// 上下文协议测试：以独立构造的合成输入验证新旧版本隔离，不启动外部模型。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** 防止升级 2.1 时意外拓宽 2.0，或允许选择引用夹带任意对象与授权。 */
class AgentContextProtocolTest {
    /** 合成字段直接来自冻结预期，不使用被测客户端生成合法请求。 */
    private Map<String,Object> request(String version) {
        var context = new LinkedHashMap<String,Object>();
        context.put("conversationSource", Map.of("sourceMessageId","source-1","conversationEpoch","epoch-1",
                "turnSequence",1,"historyRecorded",true));
        return new LinkedHashMap<>(Map.of("schemaVersion",version,"userId","42","sessionId","session-1",
                "traceId","trace-1","runId","run-1","query","你好","context",context));
    }

    @Test void newVersionAcceptsSourceButOldVersionStillRejectsIt() {
        // 正例必须先通过，防止“拒绝全部版本”的实现把所有非法输入测试伪装为成功。
        var validator = new AgentProtocolValidator(new ObjectMapper());
        assertThatCode(() -> validator.validateRequest(request("2.1"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateRequest(request("2.0")))
                .isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test void sourceCannotContainConfirmationAndSelectionCannotContainResourceIds() {
        // 同级确认由 Spring 既有确认逻辑提供，来源结构不可自行转授交易资格。
        var validator = new AgentProtocolValidator(new ObjectMapper());
        var raw = request("2.1");
        @SuppressWarnings("unchecked") var context = (Map<String,Object>) raw.get("context");
        context.put("conversationSource", Map.of("sourceMessageId","source-1","conversationEpoch","epoch-1",
                "turnSequence",1,"historyRecorded",true,"confirmationAccepted",true));
        assertThatThrownBy(() -> validator.validateRequest(raw)).isInstanceOf(AgentServiceUnavailableException.class);
        var selectionRaw = request("2.1");
        @SuppressWarnings("unchecked") var selectedContext = (Map<String,Object>) selectionRaw.get("context");
        selectedContext.put("selectionRef",Map.of("presentationId","p-1","version",1,"ordinal",2,"resourceId","forged"));
        final var invalid = selectionRaw;
        assertThatThrownBy(() -> validator.validateRequest(invalid)).isInstanceOf(AgentServiceUnavailableException.class);
    }

    @Test void configuredHttpClientUsesV2RouteAndActualSelectedVersion() throws Exception {
        // 实际 HTTP 捕获编码而不是依赖历史 schemaVersion() 访问器判断版本。
        var mapper=new ObjectMapper();
        var received=new AtomicReference<Map<String,Object>>();
        var route=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try {
                @SuppressWarnings("unchecked") Map<String,Object> body=mapper.readValue(exchange.getRequestBody(),Map.class);
                received.set(body);route.set(exchange.getRequestURI().getPath());
                var event=Map.of("schemaVersion",body.get("schemaVersion"),"eventId","synthetic-event","eventType","run_completed",
                        "traceId",body.get("traceId"),"runId",body.get("runId"),"sequence",0,"status","succeeded","payload",Map.of("message","完成"));
                byte[] data=(mapper.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var messages=mock(AgentMessageService.class);
            when(messages.record(any(),anyMap())).thenAnswer(call -> call.getArgument(1));
            var client=new HttpAgentServiceClient("http://127.0.0.1:"+server.getAddress().getPort(),
                    "synthetic-http-only-no-production-key",2000,mapper,messages,"2.1");
            @SuppressWarnings("unchecked") var context=(Map<String,Object>)request("2.1").get("context");
            assertThat(client.run(new AgentRunRequest("42","session-1","你好","confirm","trace-1","run-1",context,"2.1")).status())
                    .isEqualTo(AgentResult.Status.COMPLETED);
            assertThat(route.get()).isEqualTo("/internal/v2/agent/runs");
            assertThat(received.get()).containsEntry("schemaVersion","2.1");
            // 部署已切 2.1，但原活动任务显式 2.0 仍发送和校验 2.0，不能静默升级。
            client.run(new AgentRunRequest("42","session-1","你好","confirm","trace-2","run-2",Map.of(),"2.0"));
            assertThat(received.get()).containsEntry("schemaVersion","2.0");
            assertThat(route.get()).isEqualTo("/internal/v2/agent/runs");
        } finally { server.stop(0); }
    }

    @Test void goalProtocolEventAuditUsesTrustedTaskFence() throws Exception {
        // 2.3确认事件也必须在受理时固定的任务围栏内重查凭证；缺少该上下文会把有效任务凭证误判为旧页面凭证。
        var mapper=new ObjectMapper();
        @SuppressWarnings("unchecked") var request=mapper.readValue(
                getClass().getResourceAsStream("/task-lifecycle-protocol/request-v22.json"),Map.class);
        request.put("schemaVersion","2.3");
        @SuppressWarnings("unchecked") var context=(Map<String,Object>)request.get("context");
        @SuppressWarnings("unchecked") var admission=(Map<String,Object>)context.get("taskAdmission");
        admission.put("protocolVersion","2.3");
        context.put("goalContext",Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,
                "goalIds",List.of("goal-order")));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)admission.get("fence");
        var taskContext=new AgentTaskExecutionContext();
        var observed=new AtomicReference<Map<String,Object>>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try {
                var eventContext=new LinkedHashMap<>(fence);eventContext.remove("scope");eventContext.remove("runId");
                var event=Map.of("schemaVersion","2.3","eventId","synthetic-goal-event","eventType","run_completed",
                        "traceId",request.get("traceId"),"runId",request.get("runId"),"sequence",0,"status","succeeded",
                        "payload",Map.of("message","完成"),"taskContext",eventContext);
                byte[] data=(mapper.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var messages=mock(AgentMessageService.class);
            when(messages.record(any(),anyMap())).thenAnswer(call -> {
                // 在消息重查发生的准确位置读取线程上下文，防止最终完成路径通过却让实时路径漏掉围栏。
                observed.set(taskContext.current());return call.getArgument(1);
            });
            var client=new HttpAgentServiceClient("http://127.0.0.1:"+server.getAddress().getPort(),
                    "synthetic-http-only-no-production-key",2000,mapper,messages,"2.1");
            client.attachTaskContext(taskContext);
            client.run(new AgentRunRequest(String.valueOf(request.get("userId")),String.valueOf(request.get("sessionId")),
                    String.valueOf(request.get("query")),"confirm",String.valueOf(request.get("traceId")),
                    String.valueOf(request.get("runId")),context,"2.3"));
            assertThat(observed.get()).isEqualTo(fence);
            assertThat(taskContext.current()).isNull();
        } finally { server.stop(0); }
    }
}
