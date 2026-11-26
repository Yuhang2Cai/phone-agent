// GR-02版本竞争测试：目标修订和真实业务效果共享任务行锁与effectVersion CAS。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 竞争结果只允许一个顺序生效；失败方不能覆盖投影或生成第二份业务效果。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG04_TEST_DB_URL:jdbc:h2:mem:ag04-revisions;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG04_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG04_TEST_DB_USER:sa}","spring.datasource.password=${AG04_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.internal-token=synthetic-test-only-token"})
class AgentGoalRevisionTest {
    @Autowired AgentGoalService goals;@Autowired UserRepository users;@Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;@Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskTurnService turns;@Autowired AgentTaskToolBridge tools;@Autowired AgentTaskFenceService fences;
    @Autowired AgentTaskRepository taskRows;
    @Autowired StoreRepository stores;@Autowired DealPackageRepository deals;
    private AgentTaskTestFixtures fixture;private String task;private Map<String,Object> base;

    @BeforeEach void setUp() {
        fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var created=fixture.selection();task=AgentTaskTestFixtures.id(created);
        long taskVersion=((Number)created.get("taskVersion")).longValue(),generation=((Number)created.get("executionGeneration")).longValue();
        long focus=((Number)fixture.directory().get("focusVersion")).longValue();
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",fixture.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",task);
        var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId","goal-store");outcome.put("description","找到门店");outcome.put("status","pending");
        outcome.put("reasonCode","awaiting_execution");outcome.put("resourceSummary",null);outcome.put("completedConditionLabels",List.of());
        outcome.put("missingConditionLabels",List.of("store_candidates"));outcome.put("allowedActions",List.of());outcome.put("goalRevision",2);
        outcome.put("planRevision",2);outcome.put("effectVersion",0);
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",task);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","partial");projection.put("outcomes",List.of(outcome));projection.put("availableActions",List.of());
        base=new LinkedHashMap<>();base.put("schemaVersion","2.3");base.put("candidateHash","a".repeat(64));base.put("expectedGoalRevision",1);
        base.put("expectedPlanRevision",1);base.put("expectedEffectVersion",0);base.put("goalRevision",2);base.put("planRevision",2);
        base.put("projection",projection);base.put("taskFence",Map.of("conversationTaskId",task,"taskVersion",taskVersion,"focusVersion",focus,"executionGeneration",generation));
    }

    @Test void staleExpectedVersionRollsBackWithoutProjection() {
        // 旧goalRevision不能覆盖当前版本；失败事务不留下请求行或目标投影。
        var stale=new LinkedHashMap<>(base);stale.put("requestId","stale-"+UUID.randomUUID());stale.put("expectedGoalRevision",2);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->goals.commitRevision(fixture.user,fixture.session,task,stale)).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=?",Integer.class,task));
        assertNull(goals.projection(fixture.user,fixture.session,task));
    }

    @Test void revisionAndBusinessEffectHaveOnlyOneValidOrder() throws Exception {
        // GR-S35：两个事务使用同一预期effectVersion竞争，提交先后由任务行锁和CAS决定。
        var revision=new LinkedHashMap<>(base);revision.put("requestId","race-"+UUID.randomUUID());
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Future<String> revise=pool.submit(()->{start.await();try{goals.commitRevision(fixture.user,fixture.session,task,revision);return "revision";}catch(ResponseStatusException e){return "conflict";}});
            Future<String> effect=pool.submit(()->{start.await();try{goals.commitBusinessEffect(fixture.user,fixture.session,task,"effect-"+UUID.randomUUID(),1,1,0,Map.of("orderId","synthetic-order"));return "effect";}catch(ResponseStatusException e){return "conflict";}});
            start.countDown();var results=List.of(revise.get(20,TimeUnit.SECONDS),effect.get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(v->!"conflict".equals(v)).count());
            assertEquals(1,results.stream().filter("conflict"::equals).count());
            assertTrue(jdbc.queryForObject("SELECT effect_version FROM agent_conversation_tasks WHERE conversation_task_id=?",Long.class,task)<=1);
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_effects WHERE conversation_task_id=?",Integer.class,task)<=1);
        } finally {pool.shutdownNow();}
    }

    @Test void realDomainWriteAndEffectVersionCommitInOneToolTransaction() {
        // GR-S25/S35：2.1目标围栏经过真实报价、明确确认和订单创建；领域事实与effectVersion必须一起提交。
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",flow.scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",flow.taskId);
        var fence=flow.fence();long taskVersion=((Number)fence.get("taskVersion")).longValue();
        var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId","goal-order");outcome.put("description","购买合成双份套餐");
        outcome.put("status","pending");outcome.put("reasonCode","awaiting_execution");outcome.put("resourceSummary",null);
        outcome.put("completedConditionLabels",List.of());outcome.put("missingConditionLabels",List.of("order_committed"));
        outcome.put("allowedActions",List.of());outcome.put("goalRevision",2);outcome.put("planRevision",2);outcome.put("effectVersion",0);
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",flow.taskId);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","partial");projection.put("outcomes",List.of(outcome));projection.put("availableActions",List.of());
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");revision.put("requestId","goal-write-"+UUID.randomUUID());
        revision.put("candidateHash","b".repeat(64));revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);
        revision.put("expectedEffectVersion",0);revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",flow.taskId,"taskVersion",taskVersion,
                "focusVersion",fence.get("focusVersion"),"executionGeneration",fence.get("executionGeneration")));
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,revision);

        Map<String,Object> goalFence=Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-order"));
        String run=(String)flow.fence().get("runId");var quoteRequest=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),
                flow.scope.session,flow.trace(),run,"goal-quote",run+":goal-quote:1",1,
                Map.of("dealId",flow.dealId,"quantity",2),flow.fence(),goalFence);
        var quote=tools.execute("create_purchase_quote",quoteRequest);flow.awaitAndConfirm(quote);
        run=(String)flow.fence().get("runId");var creation=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),
                flow.scope.session,flow.trace(),run,"goal-create-order",run+":goal-create-order:1",1,
                Map.of("quoteId",quote.get("quoteId"),"confirmationGrantId",quote.get("confirmationGrantId")),flow.fence(),goalFence);
        var executed=tools.executeWithGoalContext("create_group_buy_order",creation);var order=executed.data();

        assertEquals("PENDING_PAYMENT",order.get("status"));
        assertEquals(Map.of("goalRevision",2L,"planRevision",2L,"effectVersion",1L,"goalIds",List.of("goal-order")),executed.goalContext());
        assertEquals(1L,jdbc.queryForObject("SELECT effect_version FROM agent_conversation_tasks WHERE conversation_task_id=?",Long.class,flow.taskId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_effects WHERE conversation_task_id=?",Integer.class,flow.taskId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,flow.scope.user));
        // 真实写入后，同一目标修订的公开结果必须跟随权威效果版本前进；否则轮次完成会在最后一步整体回滚。
        var updatedOutcome=new LinkedHashMap<>(outcome);updatedOutcome.put("effectVersion",1);
        var goalResult=new LinkedHashMap<String,Object>();goalResult.put("aggregateStatus","partial");
        goalResult.put("outcomes",List.of(updatedOutcome));
        goals.commitTurnProjection(taskRows.findById(flow.taskId).orElseThrow(),goalResult);
        assertEquals(1L,jdbc.queryForObject("SELECT effect_version FROM agent_task_goals WHERE conversation_task_id=?",Long.class,flow.taskId));
        // 已提交效果使旧goalFence失效；即使动作键相同也不能越过版本检查直接读取缓存。
        assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,()->tools.execute("create_group_buy_order",creation)).getCode());
    }
}
