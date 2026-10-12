// AG03实际Java HTTP传输回归：用本机受控HTTP服务记录协议头与原始正文，不替代业务或模型验收。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 两个新内部客户端必须直连HTTP/1.1；禁止明文h2c升级使Uvicorn丢失POST正文。 */
class AgentTaskHttpTransportTest {
    private final ObjectMapper mapper=new ObjectMapper();
    // 此字符串只是测试专用签名材料，不是部署凭据或真实账户秘密。
    private final String secret="synthetic-http-transport-secret-32-characters";

    /** 空目录和可空字段必须完整保留，验证真正发出的Unicode原文与协议头。 */
    @Test void inputUsesHttp1AndKeepsExactJsonBody() throws Exception {
        var response=new LinkedHashMap<String,Object>();
        for(String key:List.of("schemaVersion","requestId","action","targetTaskId","targetTaskVersion","newRequest","reasonCode","question","intent","purpose","taskKind","focusVersion","returnTo","bodySha256","executionQuerySha256"))response.put(key,null);
        response.put("schemaVersion","2.2");response.put("requestId","input-http");
        try(var server=new RecordingServer(mapper.writeValueAsBytes(response),200)) {
            var client=new HttpAgentTaskInputClient(server.base(),secret,mapper);
            var request=new LinkedHashMap<String,Object>();request.put("requestId","input-http");request.put("query","先查门店🙂，查完再继续");request.put("button",null);
            assertEquals("input-http",client.interpret(7L,"session",request).get("requestId"));
            assertNull(server.upgrade.get(),"内部POST不能发送h2c升级头");
            assertEquals(request,mapper.readValue(server.body.get(),Map.class));assertEquals(1,server.calls.get());
        }
    }

    /** 控制请求同样不得升级；真实序列化和回执Schema校验均经过正式客户端。 */
    @Test void controlUsesHttp1WithoutLosingBody() throws Exception {
        var ack=new LinkedHashMap<String,Object>();ack.put("schemaVersion","2.2");ack.put("commandId","command-http");
        ack.put("scope",Map.of("userId","7","sessionId","session","conversationEpoch","epoch"));
        ack.put("status","processing");ack.put("writeOutcome","pending");ack.put("checkpoints",List.of());ack.put("reasonCode",null);
        try(var server=new RecordingServer(mapper.writeValueAsBytes(ack),200)) {
            var client=new HttpAgentTaskControlClient(server.base(),secret,mapper);
            var request=Map.<String,Object>of("commandId","command-http","scope",ack.get("scope"));
            assertEquals("processing",client.dispatch(request).get("status"));
            assertNull(server.upgrade.get(),"内部控制不能发送h2c升级头");
            assertEquals(request,mapper.readValue(server.body.get(),Map.class));assertEquals(1,server.calls.get());
        }
    }

    /** 协议拒绝不能自动再发一次或回退旧消息入口；一次显式调用只有一个网络请求。 */
    @Test void rejectedInputDoesNotRetryOrFallback() throws Exception {
        try(var server=new RecordingServer("{}".getBytes(StandardCharsets.UTF_8),422)) {
            var client=new HttpAgentTaskInputClient(server.base(),secret,mapper);
            assertThrows(AgentServiceUnavailableException.class,()->client.interpret(7L,"session",Map.of("requestId","rejected")));
            assertEquals(1,server.calls.get());
        }
    }

    /** 记录服务只承载传输边界；关闭时释放本例随机端口，不接触真实Agent或共享设备。 */
    private static final class RecordingServer implements AutoCloseable {
        private final HttpServer server;
        final AtomicReference<String> upgrade=new AtomicReference<>();
        final AtomicReference<String> body=new AtomicReference<>();
        final AtomicInteger calls=new AtomicInteger();
        RecordingServer(byte[] response,int status) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                // 先保存实际协议头和正文，再返回固定边界结果；不解析或模拟任务决策。
                calls.incrementAndGet();upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
                body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);
                exchange.getResponseBody().write(response);exchange.close();
            });server.start();
        }
        /** 返回本例本机地址，不允许测试调用其他服务。 */
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        /** 无论断言成败均关闭本例拥有的服务。 */
        @Override public void close(){server.stop(0);}
    }
}
