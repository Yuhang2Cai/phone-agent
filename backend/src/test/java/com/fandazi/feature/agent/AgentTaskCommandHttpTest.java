// 任务命令HTTP测试：Spring真实安全链；Agent控制边界用显式本地HTTP替身，不冒称跨库链路。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

/** 只验收命令HTTP结构与持久交接组件，真实Agent停点及跨库故障后续单列。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-command-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false",
    "agent.internal-token=synthetic-control-boundary-only-secret-32-characters"})
@AutoConfigureMockMvc
class AgentTaskCommandHttpTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<Map<String,Object>> CAPTURE=new CopyOnWriteArrayList<>();
    private static final Map<String,Map<String,Object>> RECEIPTS=new ConcurrentHashMap<>();
    private static final AtomicBoolean DROP_NEXT=new AtomicBoolean(false);
    private static final HttpServer AGENT=createBoundary();
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired UserRepository users;
    @Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;

    /** 替身只回明确检查点回执，不调用模型、工具或准备任何业务凭证。 */
    private static HttpServer createBoundary() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/v2/agent/task-controls",exchange -> {
                try {
                    // GET只回读替身已持久保存的同命令ACK；回执丢失测试不能借GET触发apply。
                    if("GET".equals(exchange.getRequestMethod())) {
                        String path=exchange.getRequestURI().getPath();var saved=RECEIPTS.get(path.substring(path.lastIndexOf('/')+1));
                        if(saved==null){exchange.sendResponseHeaders(404,-1);return;}
                        byte[] data=JSON.writeValueAsBytes(saved);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);return;
                    }
                    @SuppressWarnings("unchecked") var raw=(Map<String,Object>)JSON.readValue(exchange.getRequestBody(),Map.class);
                    CAPTURE.add(raw);
                    @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)raw.get("targets");
                    var checkpoints=new ArrayList<Map<String,Object>>();
                    for(var target:targets) {
                        var point=new LinkedHashMap<String,Object>();point.put("conversationTaskId",target.get("conversationTaskId"));
                        point.put("runId",target.get("runId"));point.put("checkpointVersion",1);checkpoints.add(point);
                    }
                    var ack=new LinkedHashMap<String,Object>();ack.put("schemaVersion","2.2");ack.put("commandId",raw.get("commandId"));
                    ack.put("scope",raw.get("scope"));ack.put("status","checkpoint_saved");ack.put("writeOutcome","none");
                    ack.put("checkpoints",checkpoints);ack.put("reasonCode",null);
                    RECEIPTS.put((String)raw.get("commandId"),ack);
                    // 模拟Agent已经保存ACK但HTTP断连；重试必须查原命令，不重新产生业务动作。
                    if(DROP_NEXT.compareAndSet(true,false)){exchange.sendResponseHeaders(503,-1);return;}
                    byte[] data=JSON.writeValueAsBytes(ack);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
                } finally {exchange.close();}
            });server.start();return server;
        } catch(Exception error) {throw new IllegalStateException("合成控制边界无法启动",error);}
    }
    /** 只改变测试受控Agent地址，生产配置不指向替身。 */
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("agent.service.base-url",() -> "http://127.0.0.1:"+AGENT.getAddress().getPort());
    }
    /** 本测试拥有的边界服务结束后释放端口，不停止其他进程。 */
    @AfterAll static void closeBoundary() {AGENT.stop(0);}

    @Test void pauseMustProducePersistentCommandInsteadOfMissingRoute() throws Exception {
        // 初始红测必须得到业务断言404→200，而非缺类、编译或服务初始化错误。
        var fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var task=fixture.selection();
        var request=Map.of("requestId","synthetic-command","action","pause","targetTaskId",AgentTaskTestFixtures.id(task),
                "expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",fixture.directory().get("focusVersion"));
        var identity=new UsernamePasswordAuthenticationToken(fixture.user,null,List.of());
        var response=mvc.perform(post("/api/agent/sessions/{id}/task-commands",fixture.session)
                .with(authentication(identity)).contentType("application/json").content(JSON.writeValueAsString(request))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),"暂停控制必须返回持久命令而非404");
        assertEquals("committed",JSON.readTree(response.getContentAsString()).get("state").asText());assertNull(fixture.directory().get("currentTask"));
        var repeated=mvc.perform(post("/api/agent/sessions/{id}/task-commands",fixture.session).with(authentication(identity))
            .contentType("application/json").content(JSON.writeValueAsString(request))).andReturn().getResponse();
        assertEquals(response.getContentAsString(),repeated.getContentAsString());
        String id=JSON.readTree(response.getContentAsString()).get("commandId").asText();assertEquals(1,CAPTURE.stream().filter(c->id.equals(c.get("commandId"))).count());
    }
    @Test void lostAckIsRecoveredByReadOnlyLookup() throws Exception {
        // 明确HTTP故障：首次503但替身保存ACK，recheck只GET原命令并完成Spring提交。
        var fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var task=fixture.selection();var identity=new UsernamePasswordAuthenticationToken(fixture.user,null,List.of());
        var request=Map.of("requestId","lost-ack","action","pause","targetTaskId",AgentTaskTestFixtures.id(task),"expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",fixture.directory().get("focusVersion"));
        DROP_NEXT.set(true);var first=mvc.perform(post("/api/agent/sessions/{id}/task-commands",fixture.session).with(authentication(identity))
            .contentType("application/json").content(JSON.writeValueAsString(request))).andReturn().getResponse();
        assertEquals(200,first.getStatus());var body=JSON.readTree(first.getContentAsString());assertEquals("prepared",body.get("state").asText());String id=body.get("commandId").asText();
        var rechecked=mvc.perform(post("/api/agent/sessions/{id}/task-commands/{cmd}/recheck",fixture.session,id).with(authentication(identity))).andReturn().getResponse();
        assertEquals(200,rechecked.getStatus());assertEquals("committed",JSON.readTree(rechecked.getContentAsString()).get("state").asText());
        assertEquals(1,CAPTURE.stream().filter(c->id.equals(c.get("commandId"))).count(),"只读对账不能再POST控制");assertNull(fixture.directory().get("currentTask"));
    }
}
