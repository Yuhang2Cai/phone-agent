// GR-08目标恢复并发测试：使用真实Spring事务和数据库行锁验证跨线程请求只有一个有效受理。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 该类覆盖 GR-S21 的同体重试和同版本竞争。并发调用使用容器代理后的
 * {@link AgentGoalRecoveryService}，因此每个线程都进入独立真实事务，不以 synchronized 测试替身冒充数据库锁。
 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag04-goal-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true",
    "agent.goal-recovery.enabled=true","security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789"})
class AgentGoalConcurrencyTest {
    @Autowired UserRepository users;
    @Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;
    @Autowired AgentGoalService goals;
    @Autowired AgentGoalRecoveryService recoveries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    /** 建立当前前台 2.3 任务和一个允许只读重试的未完成目标，不预写恢复行、轮次或业务成功。 */
    private GoalCase fixture() {
        var scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var task=scope.selection();String taskId=AgentTaskTestFixtures.id(task);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);

        var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId","goal-read");outcome.put("description","查询合成停车信息");
        outcome.put("status","recoverable");outcome.put("reasonCode","read_temporarily_failed");outcome.put("resourceSummary",null);
        outcome.put("completedConditionLabels",List.of());outcome.put("missingConditionLabels",List.of("parking_info"));
        outcome.put("allowedActions",List.of("retry_reads"));outcome.put("goalRevision",2);outcome.put("planRevision",2);
        outcome.put("effectVersion",0);

        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");
        projection.put("conversationTaskId",taskId);projection.put("taskVersion",task.get("taskVersion"));
        projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","recoverable");projection.put("outcomes",List.of(outcome));
        projection.put("availableActions",List.of("retry_reads"));

        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");
        revision.put("requestId","initial-"+UUID.randomUUID());revision.put("candidateHash","a".repeat(64));
        revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);revision.put("expectedEffectVersion",0);
        revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",task.get("taskVersion"),
            "focusVersion",scope.directory().get("focusVersion"),"executionGeneration",task.get("executionGeneration")));
        goals.commitRevision(scope.user,scope.session,taskId,revision);
        return new GoalCase(scope,taskId);
    }

    /** 构造严格公开恢复正文；调用方只能声明动作和期望版本，不能携带工具或成功结果。 */
    private Map<String,Object> request(String requestId) {
        var value=new LinkedHashMap<String,Object>();value.put("schemaVersion","2.3");value.put("requestId",requestId);
        value.put("goalIds",List.of("goal-read"));value.put("action","retry_reads");
        value.put("expectedGoalRevision",2);value.put("expectedPlanRevision",2);value.put("expectedEffectVersion",0);
        return value;
    }

    /** 同一恢复正文由八个事务同时提交时，全部调用都必须回到同一回执且只能形成一条受理事实。 */
    @Test void grS21ConcurrentSameRequestReturnsOneReceiptAndOneSource() throws Exception {
        var value=fixture();String requestId="same-"+UUID.randomUUID();var body=request(requestId);
        long sourcesBefore=count("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",value.scope.session);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
        try {
            var futures=new ArrayList<Future<Map<String,Object>>>();
            for(int index=0;index<8;index++)futures.add(pool.submit(()->{start.await();return recoveries.accept(
                value.scope.user,value.scope.session,value.taskId,new LinkedHashMap<>(body));}));
            start.countDown();var receipts=new ArrayList<Map<String,Object>>();
            for(var future:futures)receipts.add(future.get(20,TimeUnit.SECONDS));
            // JSON 数值经数据库回读可能由 Integer 变成 Long；按协议树比较正文，避免把 Java 数值实现类型误当业务差异。
            var canonical=json.readTree(json.writeValueAsBytes(receipts.get(0)));
            for(var receipt:receipts)assertEquals(canonical,json.readTree(json.writeValueAsBytes(receipt)));
            assertEquals("accepted",receipts.get(0).get("state"));
        } finally {pool.shutdownNow();}

        assertEquals(1,count("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=? AND action='retry_reads'",value.taskId));
        assertEquals(sourcesBefore+1,count("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",value.scope.session));
        assertZeroBusinessSideEffects(value);
    }

    /** 两个不同 requestId 抢同一目标三版本时只能有一个受理，失败方必须得到版本竞争而非第二条恢复。 */
    @Test void grS21DifferentRequestsForSameVersionHaveExactlyOneWinner() throws Exception {
        var value=fixture();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(
                pool.submit(()->acceptOutcome(value,request("first-"+UUID.randomUUID()),start)),
                pool.submit(()->acceptOutcome(value,request("second-"+UUID.randomUUID()),start)));
            start.countDown();var outcomes=List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("accepted"::equals).count());
            assertEquals(1,outcomes.stream().filter("conflict"::equals).count());
        } finally {pool.shutdownNow();}
        assertEquals(1,count("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=? AND action='retry_reads'",value.taskId));
        assertZeroBusinessSideEffects(value);
    }

    /** 在起跑栅栏后执行一次真实服务调用；只把预期 409 映射为竞争结果，其他异常继续使测试失败。 */
    private String acceptOutcome(GoalCase value,Map<String,Object> body,CountDownLatch start) throws Exception {
        start.await();
        try {recoveries.accept(value.scope.user,value.scope.session,value.taskId,body);return "accepted";}
        catch(ResponseStatusException conflict) {
            assertEquals(409,conflict.getStatusCode().value());return "conflict";
        }
    }

    /** 受理动作只产生恢复行和可信来源；任何业务、工具或轮次增量都说明 202 被误当成执行。 */
    private void assertZeroBusinessSideEffects(GoalCase value) {
        assertEquals(0,count("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=?",value.scope.session));
        for(String table:List.of("agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))
            assertEquals(0,count("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",value.scope.user),"意外业务副作用："+table);
    }

    /** 使用参数绑定读取单个计数，避免测试把全库其他合成数据计入当前 scope。 */
    private long count(String sql,Object argument){return jdbc.queryForObject(sql,Long.class,argument);}

    /** 只保存权威合成 scope 和 taskId，预期结果不从被测恢复行反推。 */
    private record GoalCase(AgentTaskTestFixtures scope,String taskId) {}
}
