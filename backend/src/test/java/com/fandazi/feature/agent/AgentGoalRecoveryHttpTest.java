// AG04公开目标恢复HTTP测试：验证本人投影、动作白名单、幂等受理和原请求只读回查。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实安全链、Flyway、JPA事务和MockMvc核对公开接口。
 * 测试中的Agent客户端仅作越界探针：受理恢复动作不得直接调用模型或业务执行链。
 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG04_GOAL_TEST_DB_URL:jdbc:h2:mem:ag04-goal-recovery-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG04_GOAL_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG04_GOAL_TEST_DB_USER:sa}","spring.datasource.password=${AG04_GOAL_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "agent.task-lifecycle.enabled=true","agent.goal-recovery.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789"})
@AutoConfigureMockMvc
class AgentGoalRecoveryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;
    @Autowired AgentGoalService goals;
    @Autowired ApplicationContext context;
    @MockBean AgentServiceClient model;

    /**
     * AG05 统一性能进程必须在同一测试端点建立可恢复目标，并通过产品服务回读投影。
     * 该测试防止在线桥只识别普通任务 seed，导致性能清单虽标注恢复旅程却无法实际执行。
     */
    @Test void onlineFixtureBridgeExposesGoalRecoveryPreparationWithoutGoldFields() {
        var bridge=new AgentTaskOnlineFixtureBridge(context);
        @SuppressWarnings("unchecked") var seeded=(Map<String,Object>)bridge.call(
            Map.of("op","goalSeed","kind","goal-recovery"));
        assertNotNull(seeded.get("userId"));assertNotNull(seeded.get("sessionId"));assertNotNull(seeded.get("taskId"));
        @SuppressWarnings("unchecked") var projection=(Map<String,Object>)bridge.call(Map.of(
            "op","goalProjection","userId",seeded.get("userId"),"sessionId",seeded.get("sessionId"),
            "taskId",seeded.get("taskId")));
        assertEquals("recoverable",projection.get("aggregateStatus"));
        assertFalse(projection.containsKey("expected"));
    }

    /** HTTP安全链只接受已认证的Long账号主体，用户身份不从路径、查询或正文读取。 */
    private UsernamePasswordAuthenticationToken identity(Long user) {
        return new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** 构造一个已提交2.3目标投影；已完成目标与待恢复目标并存，便于核对部分结果保留。 */
    private GoalCase fixture(String aggregate,String targetStatus,List<String> actions) {
        var scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var task=scope.selection();String taskId=AgentTaskTestFixtures.id(task);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);

        var done=outcome("goal-done","预订晚餐","satisfied","completed",List.of("order_created"),List.of(),List.of());
        var pending=outcome("goal-parking","查询停车信息",targetStatus,
            "unknown_outcome".equals(targetStatus)?"write_outcome_unknown":"read_temporarily_failed",
            List.of(),List.of("parking_info"),actions);
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");
        projection.put("conversationTaskId",taskId);projection.put("taskVersion",task.get("taskVersion"));
        projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus",aggregate);projection.put("outcomes",List.of(done,pending));
        projection.put("availableActions",actions);

        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");
        revision.put("requestId","initial-"+UUID.randomUUID());revision.put("candidateHash","a".repeat(64));
        revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);revision.put("expectedEffectVersion",0);
        revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",task.get("taskVersion"),
            "focusVersion",scope.directory().get("focusVersion"),"executionGeneration",task.get("executionGeneration")));
        goals.commitRevision(scope.user,scope.session,taskId,revision);
        return new GoalCase(scope,taskId,projection);
    }

    /** 公开目标结果只使用协议字段；测试不把执行键、授权或完整工具响应伪造成成功证据。 */
    private Map<String,Object> outcome(String goalId,String description,String status,String reason,
            List<String> completed,List<String> missing,List<String> actions) {
        var value=new LinkedHashMap<String,Object>();value.put("goalId",goalId);value.put("description",description);
        value.put("status",status);value.put("reasonCode",reason);value.put("resourceSummary",null);
        value.put("completedConditionLabels",completed);value.put("missingConditionLabels",missing);
        value.put("allowedActions",actions);value.put("goalRevision",2);value.put("planRevision",2);
        value.put("effectVersion",0);return value;
    }

    /** 创建严格公开恢复正文；客户端不能传成功状态、工具、来源或确认凭证。 */
    private Map<String,Object> request(String requestId,String action) {
        var value=new LinkedHashMap<String,Object>();value.put("schemaVersion","2.3");value.put("requestId",requestId);
        value.put("goalIds",List.of("goal-parking"));value.put("action",action);
        value.put("expectedGoalRevision",2);value.put("expectedPlanRevision",2);value.put("expectedEffectVersion",0);
        return value;
    }

    /** 统计恢复动作、真实轮次与四类业务写，受理动作只能新增一条恢复请求和一条可信来源。 */
    private Map<String,Long> effects(String taskId,String sessionId) {
        var counts=new LinkedHashMap<String,Long>();
        counts.put("recoveries",jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=? AND action<>'revision'",Long.class,taskId));
        counts.put("sources",jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",Long.class,sessionId));
        for(String table:List.of("agent_task_turns","agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))
            counts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        return counts;
    }

    @Test void ownerReadsProjectionWhileAnonymousAndForeignOwnerCannotProbeTask() throws Exception {
        // GR-S27：taskId不是访问凭证；跨账号与不存在统一404，未登录先由安全链返回401。
        var value=fixture("recoverable","recoverable",List.of("retry_reads","drop_remaining"));
        mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goals",value.scope.session,value.taskId))
            .andExpect(status().isUnauthorized());
        String body=mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goals",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(json.readTree(json.writeValueAsString(value.projection)),json.readTree(body));

        var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goals",value.scope.session,value.taskId)
            .with(authentication(identity(other.user)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goals",other.session,value.taskId)
            .with(authentication(identity(other.user)))).andExpect(status().isNotFound());
        verifyNoInteractions(model);
    }

    @Test void grS21SameRequestReturnsOriginalReceiptAndChangedBodyConflicts() throws Exception {
        // 同requestId同体必须返回逐字相同原回执；异体不能新增来源、轮次或第二个恢复行。
        var value=fixture("recoverable","recoverable",List.of("retry_reads","drop_remaining"));
        String requestId="recover-"+UUID.randomUUID();var body=request(requestId,"retry_reads");var before=effects(value.taskId,value.scope.session);
        String first=mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String repeated=mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(json.readTree(first),json.readTree(repeated));assertEquals("accepted",json.readTree(first).path("state").asText());

        var altered=request(requestId,"drop_remaining");
        mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json").content(json.writeValueAsBytes(altered)))
            .andExpect(status().isConflict());
        // 同一三版本即使换新requestId也不能并行建立第二轮恢复，避免按钮连点产生多次有效受理。
        mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json")
            .content(json.writeValueAsBytes(request("second-"+UUID.randomUUID(),"retry_reads"))))
            .andExpect(status().isConflict());
        var after=effects(value.taskId,value.scope.session);
        assertEquals(before.get("recoveries")+1,after.get("recoveries"));assertEquals(before.get("sources")+1,after.get("sources"));
        for(String key:List.of("agent_task_turns","agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))
            assertEquals(before.get(key),after.get(key),"受理恢复动作不得直接产生业务副作用："+key);

        String read=mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries/{request}",value.scope.session,value.taskId,requestId)
            .with(authentication(identity(value.scope.user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(json.readTree(first),json.readTree(read));
        // 公开恢复受理行不能从内部revision回执接口读出，防止202受理被旧调用方误判成已提交目标修订。
        var hidden=assertThrows(ResponseStatusException.class,
            ()->goals.revision(value.scope.user,value.scope.session,value.taskId,requestId));
        assertEquals(404,hidden.getStatusCode().value());
        // 原请求身份同样受完整scope约束；未知ID和其他账号都按404处理且不会补建恢复行。
        var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries/{request}",value.scope.session,value.taskId,requestId)
            .with(authentication(identity(other.user)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries/{request}",value.scope.session,value.taskId,"missing-request")
            .with(authentication(identity(value.scope.user)))).andExpect(status().isNotFound());
        assertEquals(after,effects(value.taskId,value.scope.session));verifyNoInteractions(model);
    }

    @Test void serverRejectsStaleForbiddenAndInjectedRecoveryBeforeAnyWrite() throws Exception {
        // 动作必须同时来自顶层availableActions和目标自身allowedActions；客户端不能自报成功或工具。
        var value=fixture("recoverable","recoverable",List.of("retry_reads","drop_remaining"));var before=effects(value.taskId,value.scope.session);
        var stale=request("stale-"+UUID.randomUUID(),"retry_reads");stale.put("expectedGoalRevision",1);
        mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json").content(json.writeValueAsBytes(stale)))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json")
            .content(json.writeValueAsBytes(request("forbidden-"+UUID.randomUUID(),"reprepare_remaining"))))
            .andExpect(status().isConflict());
        var forged=request("forged-"+UUID.randomUUID(),"retry_reads");forged.put("succeeded",true);forged.put("toolName","create_group_buy_order");
        mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json").content(json.writeValueAsBytes(forged)))
            .andExpect(status().isBadRequest());
        assertEquals(before,effects(value.taskId,value.scope.session));verifyNoInteractions(model);
    }

    @Test void unknownWriteOnlyAcceptsReadOnlyRecheckAndNeverCreatesAnotherExecution() throws Exception {
        // 未知写只能核查原结果；202表示请求已受理，不表示原业务已提交或可以换键重发。
        var value=fixture("unknown_outcome","unknown_outcome",List.of("recheck_original"));var before=effects(value.taskId,value.scope.session);
        var invalid=request("unknown-retry-"+UUID.randomUUID(),"retry_reads");
        mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json").content(json.writeValueAsBytes(invalid)))
            .andExpect(status().isConflict());
        String response=mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json")
            .content(json.writeValueAsBytes(request("unknown-check-"+UUID.randomUUID(),"recheck_original"))))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode receipt=json.readTree(response);assertEquals("accepted",receipt.path("state").asText());assertEquals("recheck_original",receipt.path("action").asText());
        var after=effects(value.taskId,value.scope.session);assertEquals(before.get("recoveries")+1,after.get("recoveries"));
        for(String key:List.of("agent_task_turns","agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))assertEquals(before.get(key),after.get(key));
        verifyNoInteractions(model);
    }

    @Test void grS31DropRemainingKeepsCompletedProjectionAndDoesNotClaimAllSucceeded() throws Exception {
        // 放弃剩余目标先持久受理，不能在Agent尚未应用取消记录前改写已完成事实或伪造全部成功。
        var value=fixture("recoverable","recoverable",List.of("retry_reads","drop_remaining"));var before=effects(value.taskId,value.scope.session);
        String response=mvc.perform(post("/api/agent/sessions/{session}/tasks/{task}/goal-recoveries",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user))).contentType("application/json")
            .content(json.writeValueAsBytes(request("drop-"+UUID.randomUUID(),"drop_remaining"))))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("accepted",json.readTree(response).path("state").asText());
        String projection=mvc.perform(get("/api/agent/sessions/{session}/tasks/{task}/goals",value.scope.session,value.taskId)
            .with(authentication(identity(value.scope.user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(json.readTree(json.writeValueAsString(value.projection)),json.readTree(projection));
        var after=effects(value.taskId,value.scope.session);assertEquals(before.get("recoveries")+1,after.get("recoveries"));
        for(String key:List.of("agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))assertEquals(before.get(key),after.get(key));
        assertFalse(response.contains("all_satisfied"));verifyNoInteractions(model);
    }

    /** 测试夹具只保存合成身份和预期公开投影，不携带任何确认grant或执行键。 */
    private record GoalCase(AgentTaskTestFixtures scope,String taskId,Map<String,Object> projection) {}
}
