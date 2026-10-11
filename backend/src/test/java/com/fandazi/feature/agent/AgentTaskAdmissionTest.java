// 任务许可回读测试：真实Spring安全链核验原文摘要/来源/trace，完成只补交不重执。
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

/** Agent每次模型应用/工具/完成前回读权威许可；内部JWT不能代替本轮持久受理。 */
@SpringBootTest(properties={"spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-admissions;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false",
    "agent.internal-token=synthetic-ag03-fence-only-hmac-secret-32-characters"})
@AutoConfigureMockMvc
class AgentTaskAdmissionTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired UserRepository users;
    @Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;
    @Autowired AgentTaskFenceService fences;@Autowired ConfirmationGrantService grants;@Autowired AgentTaskExecutionContext execution;
    AgentTaskTestFixtures fixture;AgentTaskTurnService.Accepted turn;
    /** 固定合成原句，源摘要须来自Spring持久来源而非回显请求。 */
    @BeforeEach void setup(){fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var task=fixture.selection();
        turn=turns.admitTurn(fixture.user,fixture.session,new AgentTaskTurnService.Input("admission-input","合成原始查询",AgentTaskTestFixtures.id(task),((Number)task.get("taskVersion")).longValue(),((Number)fixture.directory().get("focusVersion")).longValue(),true));}
    /** 短期合成内部身份只绑定本人，不能查询其他scope。 */
    String token(){Instant now=Instant.now();return Jwts.builder().subject(String.valueOf(fixture.user)).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(AgentTaskFenceTest.KEY.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();}
    /** 只读回查不推进版本/trace或签grant。 */
    org.springframework.mock.web.MockHttpServletResponse read(String epoch) throws Exception {
        return http.perform(get("/internal/v2/task-admissions/{id}",turn.turnId()).header("Authorization","Bearer "+token())
            .param("userId",String.valueOf(fixture.user)).param("sessionId",fixture.session).param("conversationEpoch",epoch)).andReturn().getResponse();
    }
    @Test void admissionReturnsOriginalSourceAndCurrentAuthority() throws Exception {
        // 指定红测：缺许可回读路由不能假装当前许可有效，须真实200及原持久身份。
        var response=read(fixture.epoch);assertEquals(200,response.getStatus(),"必须提供权威受理回读而非404");var body=json.readTree(response.getContentAsString());
        assertEquals(turn.turnId(),body.path("admission").path("fence").path("turnId").asText());assertTrue(body.path("admission").path("mayExecuteTools").asBoolean());
        assertEquals(jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId()),body.path("traceId").asText());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("合成原始查询".getBytes(StandardCharsets.UTF_8))),body.path("bodySha256").asText());
    }
    @Test void staleAuthorityIsReadOnlyRejectedAndWrongEpochForbidden() throws Exception {
        // 即使turn未被显式fenceOff，当前task版本变化也必须在回读中撤销工具许可。
        assertEquals(403,read("old-epoch").getStatus());
        jdbc.update("UPDATE agent_conversation_tasks SET task_version=task_version+1 WHERE conversation_task_id=?",((Map<?,?>)turn.admission().get("fence")).get("conversationTaskId"));
        var response=read(fixture.epoch);assertEquals(200,response.getStatus());assertFalse(json.readTree(response.getContentAsString()).path("admission").path("mayExecuteTools").asBoolean());
    }
    @Test void completionHttpReplayReturnsSameResultAndProjectionOnce() throws Exception {
        // 完成HTTP丢失后的同结果补交只回原回执；推荐run终态保留task待跟进，业务工具数量不增加。
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)turn.admission().get("fence");var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId());
        var event=Map.<String,Object>of("schemaVersion","2.2","eventId","synthetic-admission-completion","eventType","run_completed","traceId",trace,"runId",fence.get("runId"),
            "sequence",0,"status","succeeded","payload",Map.of("message","合成推荐完成"),"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.2","traceId",trace,"runId",fence.get("runId"),"status","succeeded","intent","deal_recommendation","events",List.of(event));
        var raw=Map.<String,Object>of("schemaVersion","2.2","turnId",turn.turnId(),"fence",fence,"sourceMessageId",turn.admission().get("sourceMessageId"),"result",result);
        var first=http.perform(post("/internal/v2/task-admissions/{id}/completion",turn.turnId()).header("Authorization","Bearer "+token()).contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
        assertEquals(200,first.getStatus());var directory=fixture.directory();
        var again=http.perform(post("/internal/v2/task-admissions/{id}/completion",turn.turnId()).header("Authorization","Bearer "+token()).contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
        assertEquals(200,again.getStatus());assertEquals(json.readTree(first.getContentAsString()),json.readTree(again.getContentAsString()));assertEquals(directory,fixture.directory());
        var authority=json.readTree(read(fixture.epoch).getContentAsString());assertEquals("completed",authority.path("admission").path("admissionState").asText());assertFalse(authority.path("completedResult").isNull());
    }
    @Test void unpreparedConfirmationCannotBecomeDisplayedPendingSummary() {
        // 模型即使返回合法Schema，也不能把没有真实grant的投诉摘要写成可确认状态。
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)turn.admission().get("fence");
        var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId());
        var summary=Map.<String,Object>of("action","submit_complaint","resourceId","general","title","合成未经准备摘要","amountCents",0,"ruleVersion","complaint-v1",
            "subject","合成投诉主题","description","合成投诉正文");
        var event=Map.<String,Object>of("schemaVersion","2.2","eventId","synthetic-unprepared-confirmation","eventType","confirmation_required","traceId",trace,
            "runId",fence.get("runId"),"sequence",0,"status","waiting_confirmation","payload",Map.of("summary",summary,"actions",List.of(
                Map.of("actionId","synthetic-confirm","actionType","submit_complaint","label","确认提交","resourceId","general","enabled",true))),"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.2","traceId",trace,"runId",fence.get("runId"),"status","waiting_confirmation","intent","complaint","events",List.of(event));
        var completion=Map.<String,Object>of("schemaVersion","2.2","turnId",turn.turnId(),"fence",fence,"sourceMessageId",turn.admission().get("sourceMessageId"),"result",result);
        var before=fixture.directory();assertThrows(ToolBoundaryException.class,()->turns.complete(fixture.user,fixture.session,completion));
        assertEquals(before,fixture.directory());assertTrue(sessions.owned(fixture.user,fixture.session).getPendingConfirmation().isEmpty());
    }
    @Test void revisionRevokesOnlyCurrentTaskAndReplayPreservesNewPreparation() {
        // 两个同会话任务使用同资源，也必须只撤销被修订任务；重放旧撤销回执不能误撤销后来新准备。
        var f=new AgentTaskTestFixtures(users,sessions,registry,jdbc);String paused=f.seedPaused();var task=f.selection();
        var admitted=turns.admitTurn(f.user,f.session,new AgentTaskTurnService.Input("revision-input","数量改为2份",AgentTaskTestFixtures.id(task),
            ((Number)task.get("taskVersion")).longValue(),((Number)f.directory().get("focusVersion")).longValue(),true));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)admitted.admission().get("fence");
        var other=new LinkedHashMap<>(fence);other.put("conversationTaskId",paused);
        String old=execution.within(fence,()->grants.issueForAction(f.user,f.session,"SUBMIT_COMPLAINT","general",0,"complaint-v1").getGrantId());
        String untouched=execution.within(other,()->grants.issueForAction(f.user,f.session,"SUBMIT_COMPLAINT","general",0,"complaint-v1").getGrantId());
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,admitted.turnId());
        var raw=Map.<String,Object>of("schemaVersion","2.2","fence",fence,"traceId",trace,"actionVersion",2,"changedFields",List.of("quantity"));
        var receipt=fences.invalidatePreparation(raw);assertTrue(grants.owned(f.user,f.session,old).isRevoked(),"修订必须撤销旧授权");
        assertFalse(grants.owned(f.user,f.session,untouched).isRevoked());
        String fresh=execution.within(fence,()->grants.issueForAction(f.user,f.session,"SUBMIT_COMPLAINT","general",0,"complaint-v1").getGrantId());
        assertEquals(receipt,fences.invalidatePreparation(raw));assertFalse(grants.owned(f.user,f.session,fresh).isRevoked());
        var changed=new LinkedHashMap<>(raw);changed.put("changedFields",List.of("reason"));
        assertThrows(ToolBoundaryException.class,()->fences.invalidatePreparation(changed));assertFalse(grants.owned(f.user,f.session,fresh).isRevoked());
    }
    @Test void invalidRevisionFieldsAndIdentityCannotRevokeGrant() throws Exception {
        // 真实HTTP安全链与短事务：未经鉴权/小数版本/额外字段拒绝，不能消费、撤销或延长原授权。
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)turn.admission().get("fence");
        String id=execution.within(fence,()->grants.issueForAction(fixture.user,fixture.session,"SUBMIT_COMPLAINT","general",0,"complaint-v1").getGrantId());
        var expires=grants.owned(fixture.user,fixture.session,id).getExpiresAt();
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId());
        var raw=Map.<String,Object>of("schemaVersion","2.2","fence",fence,"traceId",trace,"actionVersion",2,"changedFields",List.of("quantity"));
        assertEquals(403,http.perform(post("/internal/v2/task-preparations/invalidate").contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse().getStatus());
        for(var invalid:List.of(Map.of("actionVersion",2.5),Map.of("changedFields",List.of("confirmationGrantId")),Map.of("unexpected",true))){
            var changed=new LinkedHashMap<>(raw);changed.putAll(invalid);
            assertEquals(400,http.perform(post("/internal/v2/task-preparations/invalidate").header("Authorization","Bearer "+token())
                .contentType("application/json").content(json.writeValueAsString(changed))).andReturn().getResponse().getStatus());
        }
        assertFalse(grants.owned(fixture.user,fixture.session,id).isRevoked());assertEquals(expires,grants.owned(fixture.user,fixture.session,id).getExpiresAt());
        assertEquals(200,http.perform(post("/internal/v2/task-preparations/invalidate").header("Authorization","Bearer "+token())
            .contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse().getStatus());
        assertTrue(grants.owned(fixture.user,fixture.session,id).isRevoked());
    }
}
