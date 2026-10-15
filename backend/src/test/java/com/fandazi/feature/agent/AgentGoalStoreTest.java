// GR-02目标存储测试：公开投影、请求幂等和归属在同一Spring短事务中提交。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 使用独立H2组件库验证事务行为；真实MySQL在GR-02.3使用同一服务复验。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG04_TEST_DB_URL:jdbc:h2:mem:ag04-goals;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG04_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG04_TEST_DB_USER:sa}","spring.datasource.password=${AG04_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.internal-token=synthetic-test-only-token"})
class AgentGoalStoreTest {
    @Autowired AgentGoalService goals;@Autowired UserRepository users;@Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;@Autowired JdbcTemplate jdbc;
    private AgentTaskTestFixtures fixture;private String task;private long taskVersion;private long generation;private long focusVersion;

    @BeforeEach void setUp() {
        fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var created=fixture.selection();task=AgentTaskTestFixtures.id(created);
        taskVersion=((Number)created.get("taskVersion")).longValue();generation=((Number)created.get("executionGeneration")).longValue();
        focusVersion=((Number)fixture.directory().get("focusVersion")).longValue();
        // 模拟由GR-01功能门禁选择的新任务版本；身份仍取已鉴权会话，客户端不能设置用户或epoch。
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",fixture.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",task);
    }

    private Map<String,Object> request(String requestId) {
        /** 人工构造公开投影，预期独立于生产服务返回值。 */
        var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId","goal-store");
        outcome.put("description","找到预算内的火锅店");outcome.put("status","pending");
        outcome.put("reasonCode","awaiting_execution");outcome.put("resourceSummary",null);
        outcome.put("completedConditionLabels",List.of());outcome.put("missingConditionLabels",List.of("store_candidates"));
        outcome.put("allowedActions",List.of());outcome.put("goalRevision",2);outcome.put("planRevision",2);outcome.put("effectVersion",0);
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",task);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","partial");projection.put("outcomes",List.of(outcome));projection.put("availableActions",List.of());
        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion","2.3");request.put("requestId",requestId);
        request.put("candidateHash",AgentTaskFingerprint.of(Map.of("candidate",requestId)));request.put("expectedGoalRevision",1);
        request.put("expectedPlanRevision",1);request.put("expectedEffectVersion",0);request.put("goalRevision",2);
        request.put("planRevision",2);request.put("projection",projection);
        request.put("taskFence",Map.of("conversationTaskId",task,"taskVersion",taskVersion,"focusVersion",focusVersion,"executionGeneration",generation));
        return request;
    }

    @Test void sameRequestReturnsOriginalReceiptAndChangedBodyConflicts() {
        // GR-S21：同requestId同体幂等，异体必须409且只保留一份公开目标版本。
        String id="goal-request-"+UUID.randomUUID();var raw=request(id);var first=goals.commitRevision(fixture.user,fixture.session,task,raw);
        // JSON持久化允许整型在Long/Integer间无损转换；线协议指纹相同才代表回执正文真正相同。
        assertEquals(AgentTaskFingerprint.of(first),AgentTaskFingerprint.of(goals.commitRevision(fixture.user,fixture.session,task,raw)));
        var changed=new LinkedHashMap<>(raw);changed.put("candidateHash","0".repeat(64));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->goals.commitRevision(fixture.user,fixture.session,task,changed)).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_goal_recoveries WHERE conversation_task_id=?",Integer.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_goals WHERE conversation_task_id=? AND goal_revision=2",Integer.class,task));
    }

    @Test void projectionAndVersionsCommitTogetherAndCrossOwnerCannotRead() {
        // GR-S11/S25：投影和三版本同事务；其他账号不能借taskId或requestId读取结果。
        String id="goal-request-"+UUID.randomUUID();var result=goals.commitRevision(fixture.user,fixture.session,task,request(id));
        assertEquals("committed",result.get("state"));assertEquals(2L,((Number)result.get("goalRevision")).longValue());
        var current=goals.projection(fixture.user,fixture.session,task);assertEquals(2,current.get("goalRevision"));
        var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->goals.projection(other.user,fixture.session,task)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->goals.revision(other.user,fixture.session,task,id)).getStatusCode().value());
    }
}
