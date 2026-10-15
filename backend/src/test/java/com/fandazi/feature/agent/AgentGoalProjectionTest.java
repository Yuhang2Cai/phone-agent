// GR-04目标投影事务测试：目标结果、任务停点、轮次回执和outbox必须一次提交。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实H2迁移和Spring事务验证GR-S25，不用内存替身冒充持久原子性。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag04-goal-projection;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.internal-token=synthetic-test-only-token"})
class AgentGoalProjectionTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @Autowired AgentTaskTurnService turns;@Autowired AgentGoalService goals;@Autowired AgentTaskEventOutbox outbox;@Autowired JdbcTemplate jdbc;

    /** 构造满足2.3 Schema的公开结果；测试不把执行键、grant或完整工具响应交给Spring。 */
    private Map<String,Object> outcome(String status,String reason,List<String> actions) {
        return outcome(status,reason,actions,2);
    }

    /** 允许事务回滚测试构造Schema合法但与活动合同冲突的目标版本。 */
    private Map<String,Object> outcome(String status,String reason,List<String> actions,long goalRevision) {
        var value=new LinkedHashMap<String,Object>();value.put("goalId","goal-store");value.put("description","查询合成门店");
        value.put("status",status);value.put("reasonCode",reason);value.put("resourceSummary",null);
        value.put("completedConditionLabels",List.of());value.put("missingConditionLabels",List.of("fresh_evidence"));
        value.put("allowedActions",actions);value.put("goalRevision",goalRevision);value.put("planRevision",2);value.put("effectVersion",0);return value;
    }

    /** 先提交初始目标合同版本，再受理真实turn；运行阶段只能更新同版本结果，不能偷偷推进revision。 */
    private Case fixture() {
        var scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);var task=scope.selection();String taskId=AgentTaskTestFixtures.id(task);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);
        long taskVersion=((Number)task.get("taskVersion")).longValue(),focus=((Number)scope.directory().get("focusVersion")).longValue();
        var pending=outcome("pending","awaiting_execution",List.of());
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",taskId);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","recoverable");projection.put("outcomes",List.of(pending));projection.put("availableActions",List.of());
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");revision.put("requestId","initial-"+UUID.randomUUID());
        revision.put("candidateHash","a".repeat(64));revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);
        revision.put("expectedEffectVersion",0);revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",taskVersion,"focusVersion",focus,"executionGeneration",task.get("executionGeneration")));
        goals.commitRevision(scope.user,scope.session,taskId,revision);
        var current=AgentTaskTestFixtures.current(scope.directory());var accepted=turns.admitTurn(scope.user,scope.session,
            new AgentTaskTurnService.Input("goal-turn-"+UUID.randomUUID(),"继续查询合成门店",taskId,
                ((Number)current.get("taskVersion")).longValue(),((Number)scope.directory().get("focusVersion")).longValue(),true));
        // 目标协议必须随轮次持久化并进入许可；否则真实Agent权威回读会把2.3完成结果误判成越权版本。
        assertEquals("2.3",accepted.admission().get("protocolVersion"));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)accepted.admission().get("fence");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,accepted.turnId());
        return new Case(scope,taskId,accepted,fence,trace);
    }

    /** 构造一次可恢复停点完成请求；goal_outcomes必须在最终input_required之前且使用同一版本。 */
    private Map<String,Object> completion(Case value) {
        return completion(value,2);
    }

    /** 可指定结果目标版本，用于证明晚于停点更新的投影冲突会回滚整轮事务。 */
    private Map<String,Object> completion(Case value,long goalRevision) {
        var context=new LinkedHashMap<>(value.fence);context.remove("scope");context.remove("runId");
        var recoverable=outcome("recoverable","read_temporarily_failed",List.of("retry_reads"),goalRevision);
        var goalEvent=Map.<String,Object>of("schemaVersion","2.3","eventId","goal-event-"+UUID.randomUUID(),"eventType","goal_outcomes",
            "traceId",value.trace,"runId",value.fence.get("runId"),"sequence",0,"status","waiting_input",
            "payload",Map.of("aggregateStatus","recoverable","outcomes",List.of(recoverable)),"taskContext",context);
        var inputEvent=Map.<String,Object>of("schemaVersion","2.3","eventId","input-event-"+UUID.randomUUID(),"eventType","input_required",
            "traceId",value.trace,"runId",value.fence.get("runId"),"sequence",1,"status","waiting_input",
            "payload",Map.of("question","部分目标尚未完成","inputKey","query","candidates",List.of()),"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.3","traceId",value.trace,"runId",value.fence.get("runId"),
            "status","waiting_input","intent","deal_recommendation","events",List.of(goalEvent,inputEvent));
        return Map.<String,Object>of("schemaVersion","2.3","turnId",value.accepted.turnId(),"fence",value.fence,
            "sourceMessageId",value.accepted.admission().get("sourceMessageId"),"result",result);
    }

    @Test void grS25GoalProjectionTurnStopAndOutboxCommitTogether() {
        // 完成后四类记录必须同时可见；公开投影使用recordRunStop后的taskVersion，不能停在旧围栏版本。
        var value=fixture();turns.complete(value.scope.user,value.scope.session,completion(value));
        var projection=goals.projection(value.scope.user,value.scope.session,value.taskId);
        var task=AgentTaskTestFixtures.current(value.scope.directory());
        assertEquals("recoverable",projection.get("aggregateStatus"));
        // JSON投影可能由Jackson反序列化为Integer或Long；按协议数值比较，避免测试把实现类型当成业务约束。
        assertEquals(((Number)task.get("taskVersion")).longValue(),((Number)projection.get("taskVersion")).longValue());
        assertEquals("waiting_input",AgentTaskTestFixtures.stop(task).get("kind"));
        assertEquals("completed",jdbc.queryForObject("SELECT state FROM agent_task_turns WHERE turn_id=?",String.class,value.accepted.turnId()));
        // 事件类型位于受Schema保护的event_json中；通过生产补读入口核对，避免测试依赖不存在的冗余列。
        assertTrue(outbox.read(value.scope.user,value.scope.session,0,100).stream()
            .anyMatch(event->"task_projection".equals(event.get("eventType"))));
    }

    @Test void grS25ProjectionConflictRollsBackStopTurnAndOutbox() {
        // recordRunStop先更新目录；随后目标版本冲突必须使同一事务整体回滚，不能留下幽灵停点或完成回执。
        var value=fixture();var before=AgentTaskTestFixtures.current(value.scope.directory());
        int outboxBefore=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event_outbox WHERE session_id=?",Integer.class,value.scope.session);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->turns.complete(value.scope.user,value.scope.session,completion(value,3)));

        var after=AgentTaskTestFixtures.current(value.scope.directory());
        assertEquals(((Number)before.get("taskVersion")).longValue(),((Number)after.get("taskVersion")).longValue());
        assertEquals(before.get("activeRunId"),after.get("activeRunId"));
        assertEquals(outboxBefore,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event_outbox WHERE session_id=?",Integer.class,value.scope.session));
        // 完成调用前该轮次为admitted；回滚后必须回到原值，不能留下completed或释放后的伪状态。
        assertEquals("admitted",jdbc.queryForObject("SELECT state FROM agent_task_turns WHERE turn_id=?",String.class,value.accepted.turnId()));
        var outcomes=(List<?>)goals.projection(value.scope.user,value.scope.session,value.taskId).get("outcomes");
        assertEquals("pending",((Map<?,?>)outcomes.get(0)).get("status"));
    }

    /** 2.3受理轮次拒绝省略目标结果的2.2完成信封，并保持任务、turn和原目标投影不变。 */
    @Test void v23TurnRejectsV22CompletionDowngradeWithoutChangingTask() {
        var value=fixture();var before=AgentTaskTestFixtures.current(value.scope.directory());var original=completion(value);
        var downgraded=new LinkedHashMap<String,Object>(original);downgraded.put("schemaVersion","2.2");
        @SuppressWarnings("unchecked") var originalResult=(Map<String,Object>)original.get("result");
        var result=new LinkedHashMap<String,Object>(originalResult);result.put("schemaVersion","2.2");
        @SuppressWarnings("unchecked") var originalEvents=(List<Map<String,Object>>)originalResult.get("events");
        var events=new ArrayList<Map<String,Object>>();
        for(var event:originalEvents)if(!"goal_outcomes".equals(event.get("eventType"))){
            var compatible=new LinkedHashMap<String,Object>(event);compatible.put("schemaVersion","2.2");events.add(compatible);
        }
        result.put("events",events);downgraded.put("result",result);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->turns.complete(value.scope.user,value.scope.session,downgraded));
        var after=AgentTaskTestFixtures.current(value.scope.directory());
        assertEquals(before.get("taskVersion"),after.get("taskVersion"));
        assertEquals("admitted",jdbc.queryForObject("SELECT state FROM agent_task_turns WHERE turn_id=?",String.class,value.accepted.turnId()));
        var outcomes=(List<?>)goals.projection(value.scope.user,value.scope.session,value.taskId).get("outcomes");
        assertEquals("pending",((Map<?,?>)outcomes.get(0)).get("status"));
    }

    /** 夹具返回值只保存合成身份和权威受理引用，避免测试从被测投影反推预期。 */
    private record Case(AgentTaskTestFixtures scope,String taskId,AgentTaskTurnService.Accepted accepted,Map<String,Object> fence,String trace) {}
}
