// GR-08目标恢复重启测试：重建真实Spring上下文后从原数据库读取目标、恢复回执和版本。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两个有序测试共享同一个仅用于本类的 H2 数据库。第一步提交权威投影和恢复受理后销毁
 * Spring 上下文，第二步由全新 Bean/连接池读取原行，防止测试只命中一阶缓存或进程内对象。
 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag04-goal-restart;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true",
    "agent.goal-recovery.enabled=true","security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentGoalRestartTest {
    /** 静态字段只跨测试保存公开身份和期望摘要；业务结果仍必须从重启后的数据库读取。 */
    private static Long user;
    private static String session;
    private static String taskId;
    private static String requestId;
    private static Map<String,Object> expectedReceipt;
    private static Map<String,Long> expectedCounts;

    @Autowired UserRepository users;
    @Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;
    @Autowired AgentGoalService goals;
    @Autowired AgentGoalRecoveryService recoveries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    /** 提交一个含剩余目标的投影和一次恢复受理，随后明确销毁当前 Spring 上下文。 */
    @Test @Order(1) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void persistGoalRecoveryBeforeContextRestart() {
        var scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var task=scope.selection();
        user=scope.user;session=scope.session;taskId=AgentTaskTestFixtures.id(task);requestId="restart-"+UUID.randomUUID();
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);

        var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId","goal-restart");outcome.put("description","重启后继续查询");
        outcome.put("status","recoverable");outcome.put("reasonCode","read_temporarily_failed");outcome.put("resourceSummary",null);
        outcome.put("completedConditionLabels",List.of());outcome.put("missingConditionLabels",List.of("fresh_evidence"));
        outcome.put("allowedActions",List.of("retry_reads"));outcome.put("goalRevision",2);outcome.put("planRevision",2);
        outcome.put("effectVersion",0);
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",taskId);
        projection.put("taskVersion",task.get("taskVersion"));projection.put("goalRevision",2);projection.put("planRevision",2);
        projection.put("effectVersion",0);projection.put("aggregateStatus","recoverable");projection.put("outcomes",List.of(outcome));
        projection.put("availableActions",List.of("retry_reads"));
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");
        revision.put("requestId","initial-"+UUID.randomUUID());revision.put("candidateHash","b".repeat(64));
        revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);revision.put("expectedEffectVersion",0);
        revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",task.get("taskVersion"),
            "focusVersion",scope.directory().get("focusVersion"),"executionGeneration",task.get("executionGeneration")));
        goals.commitRevision(user,session,taskId,revision);

        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion","2.3");request.put("requestId",requestId);
        request.put("goalIds",List.of("goal-restart"));request.put("action","retry_reads");
        request.put("expectedGoalRevision",2);request.put("expectedPlanRevision",2);request.put("expectedEffectVersion",0);
        expectedReceipt=new LinkedHashMap<>(recoveries.accept(user,session,taskId,request));
        expectedCounts=counts();
        assertEquals("accepted",expectedReceipt.get("state"));
    }

    /** 新上下文不得新建恢复、轮次或业务事实；原目标三版本、原 requestId 和原回执必须保持一致。 */
    @Test @Order(2)
    void grS24S25RestartReadsOriginalRowsWithoutDatabaseReset() throws Exception {
        assertNotNull(expectedReceipt,"第一步必须先建立重启前持久事实");
        // 数据库 JSON 回读可能改变 Integer/Long 的 Java 表现；协议 JSON 树相同才是跨重启正文相同。
        var expected=json.readTree(json.writeValueAsBytes(expectedReceipt));
        var actual=json.readTree(json.writeValueAsBytes(recoveries.read(user,session,taskId,requestId)));
        assertEquals(expected,actual);
        var projection=recoveries.projection(user,session,taskId);
        assertEquals(2L,((Number)projection.get("goalRevision")).longValue());
        assertEquals(2L,((Number)projection.get("planRevision")).longValue());
        assertEquals(0L,((Number)projection.get("effectVersion")).longValue());
        assertEquals(expectedCounts,counts(),"重启读取不能补建来源、恢复、轮次、工具或业务行");
    }

    /** 按原 session/task/user 记录重启前后计数，避免无关测试数据掩盖本例意外增量。 */
    private Map<String,Long> counts() {
        var result=new LinkedHashMap<String,Long>();
        result.put("recoveries",jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=?",Long.class,taskId));
        result.put("sources",jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",Long.class,session));
        result.put("turns",jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=?",Long.class,session));
        for(String table:List.of("agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))
            result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,user));
        return result;
    }
}
