// GR-04目标轮次测试：只有全部目标满足才能成功，推荐任务成功后仍按AG03规则合法跟进。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** GR-S33使用真实任务/run/turn表验证旧run终态不重开，新输入在同task建立新run。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag04-goal-turn;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.internal-token=synthetic-test-only-token"})
class AgentGoalTurnTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @Autowired AgentTaskTurnService turns;@Autowired AgentGoalService goals;@Autowired JdbcTemplate jdbc;

    @Test void grS33SatisfiedSelectionCreatesNewRunForLegalFollowup() {
        // 初始目标版本由Spring先提交；本轮只提交同版本satisfied结果，不借完成响应创建新目标版本。
        var scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var created=scope.selection();String taskId=AgentTaskTestFixtures.id(created);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);
        long taskVersion=((Number)created.get("taskVersion")).longValue(),focus=((Number)scope.directory().get("focusVersion")).longValue();
        var pending=outcome("pending","awaiting_execution");
        var initial=projection(taskId,taskVersion,"recoverable",pending);
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");revision.put("requestId","initial-"+UUID.randomUUID());
        revision.put("candidateHash","b".repeat(64));revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);revision.put("expectedEffectVersion",0);
        revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",initial);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",taskVersion,"focusVersion",focus,"executionGeneration",created.get("executionGeneration")));
        goals.commitRevision(scope.user,scope.session,taskId,revision);

        var current=AgentTaskTestFixtures.current(scope.directory());var accepted=turns.admitTurn(scope.user,scope.session,
            new AgentTaskTurnService.Input("complete-"+UUID.randomUUID(),"查询合成门店",taskId,
                ((Number)current.get("taskVersion")).longValue(),((Number)scope.directory().get("focusVersion")).longValue(),true));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)accepted.admission().get("fence");var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,accepted.turnId());String firstRun=(String)fence.get("runId");
        var satisfied=outcome("satisfied","goal_conditions_satisfied");
        var goalEvent=Map.<String,Object>of("schemaVersion","2.3","eventId","goal-"+UUID.randomUUID(),"eventType","goal_outcomes","traceId",trace,"runId",firstRun,
            "sequence",0,"status","succeeded","payload",Map.of("aggregateStatus","all_satisfied","outcomes",List.of(satisfied)),"taskContext",context);
        var doneEvent=Map.<String,Object>of("schemaVersion","2.3","eventId","done-"+UUID.randomUUID(),"eventType","run_completed","traceId",trace,"runId",firstRun,
            "sequence",1,"status","succeeded","payload",Map.of("message","已完成合成门店查询"),"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.3","traceId",trace,"runId",firstRun,"status","succeeded","intent","deal_recommendation","events",List.of(goalEvent,doneEvent));
        var completion=Map.<String,Object>of("schemaVersion","2.3","turnId",accepted.turnId(),"fence",fence,"sourceMessageId",accepted.admission().get("sourceMessageId"),"result",result);
        turns.complete(scope.user,scope.session,completion);

        var after=AgentTaskTestFixtures.current(scope.directory());assertEquals("awaiting_followup",AgentTaskTestFixtures.stop(after).get("kind"));
        var followup=turns.admitTurn(scope.user,scope.session,new AgentTaskTurnService.Input("follow-"+UUID.randomUUID(),"继续推荐",
            taskId,((Number)after.get("taskVersion")).longValue(),((Number)scope.directory().get("focusVersion")).longValue(),true));
        @SuppressWarnings("unchecked") var nextFence=(Map<String,Object>)followup.admission().get("fence");
        assertEquals(taskId,nextFence.get("conversationTaskId"));assertNotEquals(firstRun,nextFence.get("runId"));
        assertTrue(((Number)nextFence.get("executionGeneration")).longValue()>((Number)fence.get("executionGeneration")).longValue());
        assertEquals("satisfied",((Map<?,?>)((List<?>)goals.projection(scope.user,scope.session,taskId).get("outcomes")).get(0)).get("status"));
    }

    /** 构造同一目标的有限公开结果；资源摘要为空不会影响本例的任务生命周期断言。 */
    private Map<String,Object> outcome(String status,String reason) {
        var value=new LinkedHashMap<String,Object>();value.put("goalId","goal-selection");value.put("description","查询合成门店");value.put("status",status);
        value.put("reasonCode",reason);value.put("resourceSummary",null);value.put("completedConditionLabels",List.of());
        value.put("missingConditionLabels","satisfied".equals(status)?List.of():List.of("candidate_found"));value.put("allowedActions",List.of());
        value.put("goalRevision",2);value.put("planRevision",2);value.put("effectVersion",0);return value;
    }

    /** 初始目标投影只用于建立活动合同版本；运行结果由goal_outcomes事件独立提交。 */
    private Map<String,Object> projection(String taskId,long taskVersion,String aggregate,Map<String,Object> outcome) {
        var value=new LinkedHashMap<String,Object>();value.put("schemaVersion","2.3");value.put("conversationTaskId",taskId);value.put("taskVersion",taskVersion);
        value.put("goalRevision",2);value.put("planRevision",2);value.put("effectVersion",0);value.put("aggregateStatus",aggregate);
        value.put("outcomes",List.of(outcome));value.put("availableActions",List.of());return value;
    }
}
