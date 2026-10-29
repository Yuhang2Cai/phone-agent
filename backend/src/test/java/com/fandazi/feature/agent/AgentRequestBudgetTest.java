// AG08时限故障组件：本机真实TCP验证总等待，合成服务不作为在线模型性能证据。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 固定预算组件覆盖跨阶段签名传播、嵌套隔离和不断滴流不能续命。 */
class AgentRequestBudgetTest {
    private static final String SECRET="synthetic-deadline-only-key-012345678901234567890";
    /** 本地合成凭据，只供测试HTTP端点，不连接任何业务数据库。 */
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void publicEntryBindsServerBudgetAndNeverTrustsClientHeader() throws Exception {
        // 真实Servlet过滤器调用链：公开头无法延长配置，控制/订阅不抢普通业务的时间窗口。
        var filter=new AgentRequestBudgetFilter(1000);
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/agent/sessions/synthetic/task-inputs");
        request.addHeader("X-Request-Deadline","99999999999999");
        filter.doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(input,output)->{
            assertTrue(AgentRequestBudget.active());assertTrue(AgentRequestBudget.remaining(120000).toMillis()<=1000);
        });
        assertFalse(AgentRequestBudget.active());
        var stream=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/agent/sessions/synthetic/events");
        filter.doFilter(stream,new org.springframework.mock.web.MockHttpServletResponse(),(input,output)->assertFalse(AgentRequestBudget.active()));
    }

    @Test void nestedScopeKeepsDeadlineAndRestoresThread() throws Exception {
        // 嵌套服务不得重新获得120秒；退出入口之后下一请求必须没有遗留预算。
        assertFalse(AgentRequestBudget.active());
        try(var outer=AgentRequestBudget.open(1000)){
            var first=AgentRequestBudget.claims(Map.of());Thread.sleep(15);
            try(var inner=AgentRequestBudget.open(10000)){
                assertEquals(first,AgentRequestBudget.claims(Map.of()));
                assertTrue(AgentRequestBudget.remaining(10000).toMillis()<1000);
            }
        }
        assertFalse(AgentRequestBudget.active());
    }

    @Test void inputAndRunCarryIdenticalSignedDeadline() throws Exception {
        // 两个真实HTTP客户端串行运行，捕获签名载荷；公共正文不能重置第二阶段期限。
        var captures=new ArrayList<Map<String,Object>>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{
                var token=exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                synchronized(captures){captures.add(mapper.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]),Map.class));}
                exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(500,-1);
            }finally{exchange.close();}
        });server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        var input=new HttpAgentTaskInputClient(base,SECRET,mapper);
        // 生产客户端是Spring启动期单例；协议Schema解析不属于单次请求预算，测试也必须保持相同生命周期。
        var run=new HttpAgentServiceClient(base,SECRET,2000,mapper,mock(AgentMessageService.class),"2.0");
        try(var budget=AgentRequestBudget.open(3000)){
            assertThrows(AgentServiceUnavailableException.class,()->input.interpret(42L,"session",Map.of()));
            assertThrows(AgentServiceUnavailableException.class,()->run.run(new AgentRunRequest("42","session","你好","recommend","trace")));
            assertEquals(2,captures.size());assertNotNull(captures.get(0).get(AgentRequestBudget.CLAIM),"归属解释没有传递入口截止");
            assertEquals(captures.get(0).get(AgentRequestBudget.CLAIM),captures.get(1).get(AgentRequestBudget.CLAIM));
        }finally{server.stop(0);}
    }

    @Test void tricklingStreamCannotResetTotalWaitingTime() throws Exception {
        // 服务每60毫秒发一行，均小于单次读取超时；只有总截止才能阻止无限延长。
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var streamEnded=new AtomicReference<Boolean>(false);
        server.createContext("/",exchange->{
            try{
                var raw=mapper.readValue(exchange.getRequestBody(),Map.class);exchange.sendResponseHeaders(200,0);
                for(int i=0;i<30;i++){
                    var event=Map.of("schemaVersion","2.0","eventId","event-"+i,"eventType","progress","sequence",i,
                        "traceId",raw.get("traceId"),"runId",raw.get("runId"),"status","running",
                        "payload",Map.of("stepId","classify","label","合成等待","stepStatus","running"));
                    exchange.getResponseBody().write((mapper.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();Thread.sleep(60);
                }
            }catch(Exception ignored){/* 客户端按截止关闭，服务端观察到连接断开是本用例预期。 */}
            finally{streamEnded.set(true);exchange.close();}
        });server.start();
        var messages=mock(AgentMessageService.class);when(messages.record(any(),anyMap())).thenAnswer(call->call.getArgument(1));
        var client=new HttpAgentServiceClient("http://127.0.0.1:"+server.getAddress().getPort(),SECRET,2000,mapper,messages,"2.0");
        try(var budget=AgentRequestBudget.open(350)){
            long start=System.nanoTime();
            assertThrows(AgentServiceUnavailableException.class,()->client.run(new AgentRunRequest("42","session","你好","recommend","trace")));
            assertTrue((System.nanoTime()-start)/1000000<1200,"逐行读取重置了总截止");
        }finally{server.stop(0);}
        assertTrue(streamEnded.get());
    }
}
