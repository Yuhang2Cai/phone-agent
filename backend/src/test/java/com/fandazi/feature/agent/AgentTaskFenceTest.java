// 工具栅栏测试：真实Spring安全链/同库事务，先证明旧代次或旧入口不能执行2.2任务。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

/** 旧栅栏必须在任何报价/执行/业务写之前拒绝；不能用资源不存在代替栅栏拒绝。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-fence;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false",
    "agent.internal-token=synthetic-ag03-fence-only-hmac-secret-32-characters"})
@AutoConfigureMockMvc
class AgentTaskFenceTest {
    static final String KEY="synthetic-ag03-fence-only-hmac-secret-32-characters";
    @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired UserRepository users;
    @Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;
    AgentTaskTestFixtures fixture;AgentTaskTurnService.Accepted admitted;
    /** 每例新scope及真实普通轮次；2.2许可只能来自Spring受理，测试不自造task/run身份。 */
    @BeforeEach void setup(){fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var task=fixture.selection();
        admitted=turns.admitTurn(fixture.user,fixture.session,new AgentTaskTurnService.Input("tool-input","合成查询",AgentTaskTestFixtures.id(task),((Number)task.get("taskVersion")).longValue(),((Number)fixture.directory().get("focusVersion")).longValue(),true));}
    /** 合成内部JWT短期绑定账号，不使用真实凭证。 */
    String token(){Instant now=Instant.now();return Jwts.builder().subject(String.valueOf(fixture.user)).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();}
    /** v2信封绑定可信fence、原trace和确定性动作键，业务载荷不接收身份/金额扩展。 */
    Map<String,Object> request(Map<String,Object> payload){
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)admitted.admission().get("fence");String dagTask="synthetic-node";
        var raw=new LinkedHashMap<String,Object>();raw.put("schemaVersion","2.0");raw.put("userId",String.valueOf(fixture.user));raw.put("sessionId",fixture.session);
        raw.put("traceId",jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,admitted.turnId()));raw.put("runId",fence.get("runId"));
        raw.put("taskId",dagTask);raw.put("idempotencyKey",fence.get("runId")+":"+dagTask+":1");raw.put("actionVersion",1);raw.put("taskFence",fence);raw.put("payload",payload);return raw;
    }
    /** 执行真实HTTP边界，返回标准JSON；状态断言防止把404默认错误页当作业务拒绝。 */
    Map<?,?> call(String version,String tool,Map<String,Object> raw) throws Exception {
        var response=http.perform(post("/internal/{version}/tools/{tool}",version,tool).header("Authorization","Bearer "+token()).contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),"新工具入口必须返回标准结果而非缺失路由");return json.readValue(response.getContentAsString(),Map.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"generation","focus","task_version"})
    void oldGenerationCannotReachCreation(String changed) throws Exception {
        // S05/S13：资源故意不存在，旧代次/焦点/任务版本分别须先CONFLICT，不能跑到报价/凭证/领域写。
        var raw=request(Map.of("quoteId","synthetic-nonexistent-quote","confirmationGrantId","synthetic-nonexistent-grant"));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)raw.get("taskFence");
        if("focus".equals(changed))jdbc.update("UPDATE agent_sessions SET task_focus_version=task_focus_version+1 WHERE session_id=?",fixture.session);
        else {String column="generation".equals(changed)?"execution_generation":"task_version";
            jdbc.update("UPDATE agent_conversation_tasks SET "+column+"="+column+"+1 WHERE conversation_task_id=?",fence.get("conversationTaskId"));}
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions",Long.class);var result=call("v2","create_group_buy_order",raw);
        assertEquals(false,result.get("ok"));assertEquals("CONFLICT",((Map<?,?>)result.get("error")).get("code"));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions",Long.class));
    }
    @Test void legacyTransportCannotExecuteTaskProtocol() throws Exception {
        // S17：2.2任务不能通过无TaskFence的v1入口读取/执行，哪怕run和trace均正确。
        var raw=request(Map.of("question","合成问题"));raw.remove("taskFence");raw.put("schemaVersion","1.1");
        var response=http.perform(post("/internal/v1/tools/query_faq").header("Authorization","Bearer "+token()).contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
        var result=json.readValue(response.getContentAsString(),Map.class);assertEquals(false,result.get("ok"),"2.2任务必须拒绝无栅栏旧传输");
        assertEquals("FORBIDDEN",((Map<?,?>)result.get("error")).get("code"));
    }
    @Test void currentFenceReadsOnceAndRejectsChangedPayload() throws Exception {
        // 正常新入口仍可读取；同键重试只回读结果，异文不能悄悄查询另一业务对象。
        var raw=request(Map.of("question","合成问题"));var first=call("v2","query_faq",raw);assertEquals(true,first.get("ok"));assertEquals(first,call("v2","query_faq",raw));
        raw.put("payload",Map.of("question","另一合成问题"));var changed=call("v2","query_faq",raw);assertEquals(false,changed.get("ok"));assertEquals("CONFLICT",((Map<?,?>)changed.get("error")).get("code"));
    }
    @Test void foreignFenceCannotUseOwnedInternalJwt() throws Exception {
        // JWT虽属本人，栅栏scope另指他人也必须在查询/写入前403，不能只校验顶层userId。
        var raw=request(Map.of("question","合成问题"));var fence=new LinkedHashMap<>((Map<String,Object>)raw.get("taskFence"));
        fence.put("scope",Map.of("userId","99999999","sessionId",fixture.session,"conversationEpoch",fixture.epoch));raw.put("taskFence",fence);
        var response=http.perform(post("/internal/v2/tools/query_faq").header("Authorization","Bearer "+token()).contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
        assertEquals(403,response.getStatus());assertEquals("FORBIDDEN",json.readTree(response.getContentAsString()).path("error").path("code").asText());
    }
    @Test void modelSuppliedAmountIsRejectedBeforeResourceLookup() throws Exception {
        // 新传输仍复用原17工具载荷Schema，模型额外金额不能被Jackson忽略或传到报价领域。
        var raw=request(Map.of("dealId","synthetic-nonexistent-deal","quantity",2,"amountCents",1));
        var result=call("v2","create_purchase_quote",raw);assertEquals(false,result.get("ok"));assertEquals("VALIDATION_ERROR",((Map<?,?>)result.get("error")).get("code"));
    }
}
