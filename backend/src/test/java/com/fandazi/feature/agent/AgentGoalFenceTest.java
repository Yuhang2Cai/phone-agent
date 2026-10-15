// GR-06目标围栏验收：真实确认、目标修订和领域写竞争只能形成一种有效提交顺序。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 使用真实报价、确认凭证、任务行锁、目标版本和订单表验证并发边界。
 * 测试不直接插入业务订单或确认凭证，避免绕过实际授权和领域事务。
 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG04_TEST_DB_URL:jdbc:h2:mem:ag04-goal-fence;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG04_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG04_TEST_DB_USER:sa}","spring.datasource.password=${AG04_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.goal-recovery.enabled=true",
    "agent.internal-token=synthetic-gr06-goal-http-hmac-secret-32-characters"})
@AutoConfigureMockMvc
class AgentGoalFenceTest {
    private static final String KEY="synthetic-gr06-goal-http-hmac-secret-32-characters";
    @Autowired AgentGoalService goals;@Autowired UserRepository users;@Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;@Autowired AgentTaskToolBridge tools;
    @Autowired AgentTaskFenceService fences;@Autowired JdbcTemplate jdbc;@Autowired StoreRepository stores;
    @Autowired DealPackageRepository deals;@Autowired MockMvc http;

    /** 生成只绑定当前合成账号的一分钟内部JWT，不使用用户登录令牌或真实凭证。 */
    private String token(Long user) {
        Instant now=Instant.now();return Jwts.builder().subject(String.valueOf(user)).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();
    }

    /**
     * 建立2.3购买目标并完成真实报价与明确确认，返回尚未执行的旧版本建单请求。
     * 目标初始提交使用1/1/0→2/2/0，后续修订和业务写均从同一旧三版本竞争。
     */
    private Prepared preparedWrite() {
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",flow.scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",flow.taskId);
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,
                revision(flow,"initial-"+UUID.randomUUID(),1,1,0,2,2));
        Map<String,Object> goalFence=Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-order"));
        String run=(String)flow.fence().get("runId");
        var quoteRequest=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,
                "goal-quote",run+":goal-quote:1",1,Map.of("dealId",flow.dealId,"quantity",2),flow.fence(),goalFence);
        var quote=tools.execute("create_purchase_quote",quoteRequest);flow.awaitAndConfirm(quote);
        run=(String)flow.fence().get("runId");
        var write=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,
                "goal-create-order",run+":goal-create-order:1",1,
                Map.of("quoteId",quote.get("quoteId"),"confirmationGrantId",quote.get("confirmationGrantId")),flow.fence(),goalFence);
        return new Prepared(flow,write,String.valueOf(quote.get("confirmationGrantId")));
    }

    /** 构造严格公开投影；每个版本字段来自同一预期，不能用测试默认值掩盖CAS错误。 */
    private Map<String,Object> revision(AgentTaskToolTestFixtures flow,String requestId,long expectedGoal,long expectedPlan,
            long expectedEffect,long nextGoal,long nextPlan) {
        Map<String,Object> fence=flow.fence();long taskVersion=((Number)fence.get("taskVersion")).longValue();
        var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId","goal-order");outcome.put("description","购买合成双份套餐");
        outcome.put("status","pending");outcome.put("reasonCode","awaiting_execution");outcome.put("resourceSummary",null);
        outcome.put("completedConditionLabels",List.of());outcome.put("missingConditionLabels",List.of("order_committed"));
        outcome.put("allowedActions",List.of());outcome.put("goalRevision",nextGoal);outcome.put("planRevision",nextPlan);
        outcome.put("effectVersion",expectedEffect);
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",flow.taskId);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",nextGoal);projection.put("planRevision",nextPlan);
        projection.put("effectVersion",expectedEffect);projection.put("aggregateStatus","partial");
        projection.put("outcomes",List.of(outcome));projection.put("availableActions",List.of());
        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion","2.3");request.put("requestId",requestId);
        request.put("candidateHash",AgentTaskFingerprint.of(projection));request.put("expectedGoalRevision",expectedGoal);
        request.put("expectedPlanRevision",expectedPlan);request.put("expectedEffectVersion",expectedEffect);
        request.put("goalRevision",nextGoal);request.put("planRevision",nextPlan);request.put("projection",projection);
        request.put("taskFence",Map.of("conversationTaskId",flow.taskId,"taskVersion",taskVersion,
                "focusVersion",fence.get("focusVersion"),"executionGeneration",fence.get("executionGeneration")));
        return request;
    }

    @Test void revisionFirstRevokesOldGrantAndRejectsOldWriteBeforeDomainMutation() {
        // GR-S12/22：修订先提交时，旧确认状态与旧grant同事务撤销，旧请求不能产生订单或执行回执。
        var prepared=preparedWrite();var flow=prepared.flow();
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,
                revision(flow,"revision-first-"+UUID.randomUUID(),2,2,0,3,3));
        assertTrue(jdbc.queryForObject("SELECT revoked FROM confirmation_grants WHERE grant_id=?",Boolean.class,prepared.grantId()));
        assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,
                ()->tools.execute("create_group_buy_order",prepared.request())).getCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,flow.scope.user));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_effects WHERE conversation_task_id=?",Integer.class,flow.taskId));
    }

    @Test void realRevisionAndConfirmedWriteRaceHasExactlyOneWinner() throws Exception {
        // GR-S22/35：真实领域事务与目标修订同时争用任务锁，只能“修订生效”或“订单生效”之一。
        var prepared=preparedWrite();var flow=prepared.flow();
        var request=revision(flow,"concurrent-"+UUID.randomUUID(),2,2,0,3,3);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Future<String> revise=pool.submit(()->{start.await();try{goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,request);return "revision";}
                catch(ResponseStatusException conflict){return "conflict";}});
            Future<String> write=pool.submit(()->{start.await();try{tools.execute("create_group_buy_order",prepared.request());return "write";}
                catch(ToolBoundaryException conflict){return "conflict";}});
            start.countDown();var results=List.of(revise.get(20,TimeUnit.SECONDS),write.get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(value->!"conflict".equals(value)).count());
            assertEquals(1,results.stream().filter("conflict"::equals).count());
            int orders=jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,flow.scope.user);
            int effects=jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_effects WHERE conversation_task_id=?",Integer.class,flow.taskId);
            long goal=jdbc.queryForObject("SELECT goal_revision FROM agent_conversation_tasks WHERE conversation_task_id=?",Long.class,flow.taskId);
            long effect=jdbc.queryForObject("SELECT effect_version FROM agent_conversation_tasks WHERE conversation_task_id=?",Long.class,flow.taskId);
            assertTrue((goal==3&&effect==0&&orders==0&&effects==0)||(goal==2&&effect==1&&orders==1&&effects==1));
        } finally {pool.shutdownNow();}
    }

    @Test void lostRevisionAckIsRecoveredBySameRequestWithoutSecondVersion() {
        // GR-S24：把首次返回视为ACK丢失后，只读原requestId并同体重放，版本和恢复行仍各一份。
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",flow.scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",flow.taskId);
        String requestId="lost-ack-"+UUID.randomUUID();var request=revision(flow,requestId,1,1,0,2,2);
        var ignored=goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,request);
        var recovered=goals.revision(flow.scope.user,flow.scope.session,flow.taskId,requestId);
        var repeated=goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,request);
        // JSON列回读可能把整数恢复成不同Number实现；规范摘要相同才表示协议正文完全等价。
        assertEquals(AgentTaskFingerprint.of(ignored),AgentTaskFingerprint.of(recovered));
        assertEquals(AgentTaskFingerprint.of(recovered),AgentTaskFingerprint.of(repeated));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=?",Integer.class,flow.taskId));
        assertEquals(2L,jdbc.queryForObject("SELECT goal_revision FROM agent_conversation_tasks WHERE conversation_task_id=?",Long.class,flow.taskId));
    }

    @Test void goalRevisionHttpIsReachableOnlyThroughDedicatedInternalJwt() throws Exception {
        // GR-S24：全局安全链必须放行到专用内部JWT控制器；有效身份查无原请求返回404，无凭证由控制器返回403。
        var prepared=preparedWrite();var flow=prepared.flow();String requestId="missing-"+UUID.randomUUID();
        var valid=http.perform(get("/internal/v2/task-goals/revisions/{requestId}",requestId)
                .header("Authorization","Bearer "+token(flow.scope.user))
                .param("userId",String.valueOf(flow.scope.user)).param("sessionId",flow.scope.session)
                .param("conversationTaskId",flow.taskId)).andReturn().getResponse();
        assertEquals(404,valid.getStatus(),"有效内部JWT必须到达目标控制器，不能被全局用户JWT过滤器误拦");
        var missing=http.perform(get("/internal/v2/task-goals/revisions/{requestId}",requestId)
                .param("userId",String.valueOf(flow.scope.user)).param("sessionId",flow.scope.session)
                .param("conversationTaskId",flow.taskId)).andReturn().getResponse();
        assertEquals(403,missing.getStatus(),"专用控制器必须拒绝缺失内部JWT");
    }

    /** 保存一条真实待执行写请求及其旧确认身份，便于三个场景复用相同建立过程。 */
    private record Prepared(AgentTaskToolTestFixtures flow,InternalV2ToolRequest request,String grantId) {}
}
