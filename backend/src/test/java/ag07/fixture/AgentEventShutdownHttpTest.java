package ag07.fixture;

// 测试夹具放在生产扫描包之外，防止主应用显式ComponentScan纳入合成控制器与仓储。
import com.fandazi.feature.agent.*;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 一次真实本机Tomcat/SSE关闭验证：1条连接、5秒连接/收尾期限，模型/数据库/业务写均0。 */
class AgentEventShutdownHttpTest {
    /** 独立随机端口，仅登记被测生产SSE服务；不扫描生产Bean或读取部署凭据。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @EnableWebMvc
    static class ServerConfiguration {
        @Bean TomcatServletWebServerFactory webServerFactory() {
            var factory=new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getLoopbackAddress());factory.setShutdown(Shutdown.GRACEFUL);return factory;
        }
        /** 最小上下文不启用Boot自动配置，须显式登记真实DispatcherServlet，否则请求只有404。 */
        @Bean org.springframework.web.servlet.DispatcherServlet dispatcherServlet(){return new org.springframework.web.servlet.DispatcherServlet();}
        @Bean org.springframework.boot.web.servlet.ServletRegistrationBean<org.springframework.web.servlet.DispatcherServlet> servletRegistration(
                org.springframework.web.servlet.DispatcherServlet servlet) {
            var registration=new org.springframework.boot.web.servlet.ServletRegistrationBean<>(servlet,"/");
            registration.setAsyncSupported(true);return registration;
        }
        @Bean AgentEventRepository repository(){return mock(AgentEventRepository.class);}
        @Bean AgentEventService events(AgentEventRepository rows){return new AgentEventService(rows);}
        @Bean SyntheticController controller(AgentEventService events){return new SyntheticController(events);}
    }
    /** 只发送一条连接标记使真实流建立；无任务、工具、用户写入或未来心跳。 */
    @org.springframework.boot.test.context.TestComponent @RestController static class SyntheticController {
        private final AgentEventService events;
        SyntheticController(AgentEventService events){this.events=events;}
        @GetMapping(value="/synthetic-sse",produces="text/event-stream")
        public SseEmitter subscribe() throws Exception {
            var stream=events.subscribe(1L,"synthetic",null,event->{throw new AssertionError("合成空历史不得产生业务事件");});
            stream.send(SseEmitter.event().name("ready").data("synthetic"));return stream;
        }
    }
    /** 应用关闭必须发送EOF，使优雅停止不依赖业务流再写事件才能发现断连。 */
    @Test void ownedRealStreamEndsOnContextClose() throws Exception {
        var context=new AnnotationConfigServletWebServerApplicationContext();
        context.register(ServerConfiguration.class);
        var reader=Executors.newSingleThreadExecutor();java.io.InputStream body=null;
        try {
            context.refresh();
            int port=context.getWebServer().getPort();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var response=client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/synthetic-sse"))
                .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofInputStream()).get(5,TimeUnit.SECONDS);
            assertEquals(200,response.statusCode());body=response.body();
            java.io.InputStream stream=body;
            var completion=reader.submit(()->new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            context.close();
            assertTrue(completion.get(5,TimeUnit.SECONDS).contains("synthetic"));
            assertFalse(context.isActive());
        } finally {
            if(body!=null)body.close();if(context.isActive())context.close();
            reader.shutdownNow();assertTrue(reader.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
}
