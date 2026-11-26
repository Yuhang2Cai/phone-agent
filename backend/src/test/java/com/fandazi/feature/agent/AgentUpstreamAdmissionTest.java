package com.fandazi.feature.agent;

import ag07.fixture.RejectedController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.fandazi.feature.DomainExceptionHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 上游真实本机HTTP拒绝必须穿过两个生产客户端到达公开安全信封；不调用模型或数据库。 */
class AgentUpstreamAdmissionTest {
    /** 同一次调用最多发送一次；确认请求的上游429不得被包装为不透明500。 */
    @ParameterizedTest @CsvSource({"run,429,7,7","input,429,7,7","run,503,3,3","input,503,3,3",
            "run,429,garbage,1","input,503,-9,1"})
    void transportsPreserveRejectionWithoutRetry(String kind,int status,String retry,String expected) throws Exception {
        var calls=new AtomicInteger();var mapper=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After",retry);
            byte[] body="私有上游正文不得公开".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort(),secret=UUID.randomUUID().toString();
            RuntimeException error;
            if(kind.equals("run")) {
                var client=new HttpAgentServiceClient(base,secret,2000,mapper,new AgentMessageService(mock(AgentEventService.class)));
                var request=new AgentRunRequest("42","synthetic-session","查询套餐","recommend",UUID.randomUUID().toString());
                error=assertThrows(RuntimeException.class,()->client.run(request));
            } else {
                var client=new HttpAgentTaskInputClient(base,secret,mapper);
                error=assertThrows(RuntimeException.class,()->client.interpret(42L,"synthetic-session",Map.of("requestId","synthetic")));
            }
            var http=MockMvcBuilders.standaloneSetup(new RejectedController(error)).setControllerAdvice(new DomainExceptionHandler()).build();
            var response=assertDoesNotThrow(()->http.perform(get("/synthetic-upstream")).andReturn().getResponse(),"上游拒绝必须有公开安全错误信封");
            assertEquals(status,response.getStatus());assertEquals(expected,response.getHeader("Retry-After"));
            String body=response.getContentAsString(StandardCharsets.UTF_8);
            assertTrue(body.contains("error"));assertFalse(body.contains("私有上游"));
            assertFalse(body.contains("请求暂未受理"),"Spring可能已保存turn，不能把上游拒绝说成没有受理记录");
            assertEquals(1,calls.get(),"不能自动重发确认或降级旧入口");
        } finally {server.stop(0);}
    }
    /** 旧协议同样应结束既有run并保留拒绝异常，不能因兼容分支把429改为成功HTTP。 */
    @org.junit.jupiter.api.Test void oldGatewayFinalizesRunAndPreservesUpstreamStatus() {
        var sessions=mock(AgentSessionService.class);var client=mock(AgentServiceClient.class);
        var session=mock(AgentSession.class);when(session.getActiveRunId()).thenReturn("synthetic-run");
        when(sessions.start(eq(1L),eq("synthetic-session"),eq(AgentMode.CONFIRM),anyString())).thenReturn(session);
        var rejected=new AgentUpstreamAdmissionException(429,"4");when(client.run(any())).thenThrow(rejected);
        var gateway=new AgentGatewayService(sessions,client,mock(AgentEventService.class),mock(AgentCardEnrichmentService.class));
        assertSame(rejected,assertThrows(AgentUpstreamAdmissionException.class,()->gateway.handle(1L,"synthetic-session","确认",AgentMode.CONFIRM)));
        verify(sessions).finishRun(1L,"synthetic-session","synthetic-run",AgentResult.Status.FAILED);
        verify(client,times(1)).run(any());
    }

}
