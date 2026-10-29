// GR-04目标轮次测试：只有全部目标满足才能成功，推荐任务成功后仍按AG03规则合法跟进。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Autowired AgentTaskGatewayService gateway;

    @ParameterizedTest
    @ValueSource(strings={"initial","classification","advanced","text_event","tool_record","clarify","clarify_field"})
    void initialPlanningFailureClosesOnlyEmptyGoalTask(String scenario) {
        // 模型尚未建立目标即拒绝时保留原失败；已修订或携带业务正文的轮次不能借空目标例外结束。
        var scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var created=registry.create(scope.user,scope.session,"composite","composite","合成知识与购买");
        String taskId=AgentTaskTestFixtures.id(created);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);
        if("advanced".equals(scenario))jdbc.update("UPDATE agent_conversation_tasks SET goal_revision=2 WHERE conversation_task_id=?",taskId);
        var current=AgentTaskTestFixtures.current(scope.directory());
        var accepted=turns.admitTurn(scope.user,scope.session,new AgentTaskTurnService.Input("reject-"+UUID.randomUUID(),"知识与购买",taskId,
            ((Number)current.get("taskVersion")).longValue(),((Number)scope.directory().get("focusVersion")).longValue(),true));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)accepted.admission().get("fence");
        var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,accepted.turnId());
        String run=(String)fence.get("runId");var events=new ArrayList<Map<String,Object>>();
        // 已有工具账本即使结果事件被删减，也不能伪装成尚未执行的规划失败；此行仅是安全反例夹具。
        if("tool_record".equals(scenario))jdbc.update("INSERT INTO agent_tool_executions(execution_key,user_id,session_id,tool_name,fingerprint,created_at,result_json,conversation_task_id) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,?,?)",
            "synthetic-"+UUID.randomUUID(),scope.user,scope.session,"create_order","a".repeat(64),"{}",taskId);
        events.add(Map.of("schemaVersion","2.3","eventId","start-"+UUID.randomUUID(),"eventType","run_started",
            "traceId",trace,"runId",run,"sequence",0,"status","running","payload",Map.of(),"taskContext",context));
        // 生产分类器会在失败之前产生分类进度与意图事件；允许这些观察事件不等于允许工具或正文。
        if("classification".equals(scenario))for(String type:List.of("progress","intent_decided","progress")){
            var payload="progress".equals(type)?Map.<String,Object>of("stepId","classify","label","合成分类进度","stepStatus","running"):Map.<String,Object>of("intent","composite");
            events.add(Map.of("schemaVersion","2.3","eventId","classify-"+UUID.randomUUID(),"eventType",type,
                "traceId",trace,"runId",run,"sequence",events.size(),"status","running","payload",payload,"taskContext",context));
        }
        if("text_event".equals(scenario))events.add(Map.of("schemaVersion","2.3","eventId","text-"+UUID.randomUUID(),"eventType","text_delta",
            "traceId",trace,"runId",run,"sequence",1,"status","running","payload",Map.of("delta","不能伪装规划前正文"),"taskContext",context));
        var error=Map.<String,Object>of("code","VALIDATION_ERROR","message","合成规划拒绝","retryable",false);
        boolean clarify=scenario.startsWith("clarify");
        var terminal=clarify?Map.<String,Object>of("question","请说明尚未明确的目标","inputKey","clarify_field".equals(scenario)?"reason":"query","candidates",List.of()):error;
        events.add(Map.of("schemaVersion","2.3","eventId","error-"+UUID.randomUUID(),"eventType","error",
            "traceId",trace,"runId",run,"sequence",events.size(),"status","failed","payload",error,"taskContext",context));
        // 未初始化目标只允许无候选的目标/城市追问；退款原因等业务字段不能从此例外放行。
        if(clarify)events.set(events.size()-1,Map.of("schemaVersion","2.3","eventId","input-"+UUID.randomUUID(),"eventType","input_required",
            "traceId",trace,"runId",run,"sequence",events.size()-1,"status","waiting_input","payload",terminal,"taskContext",context));
        var result=new LinkedHashMap<String,Object>(Map.of("schemaVersion","2.3","runId",run,"traceId",trace,
            "status",clarify?"waiting_input":"failed","intent","composite","events",events));
        if(!clarify)result.put("error",error);
        var completion=Map.<String,Object>of("schemaVersion","2.3","turnId",accepted.turnId(),"fence",fence,
            "sourceMessageId",accepted.admission().get("sourceMessageId"),"result",result);
        if(!Set.of("initial","classification","clarify").contains(scenario)) {
            var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->turns.complete(scope.user,scope.session,completion));
            assertEquals(409,failure.getStatusCode().value());return;
        }
        // JSON持久化后的整数可能变为Integer或Long，比较完整规范JSON摘要，不能因Java数值包装类型误报重放差异。
        assertEquals(AgentTaskFingerprint.of(completion),AgentTaskFingerprint.of(turns.complete(scope.user,scope.session,completion)));
        assertEquals(AgentTaskFingerprint.of(completion),AgentTaskFingerprint.of(turns.complete(scope.user,scope.session,completion)));
        if("clarify".equals(scenario)){
            // 私有完成回执通过不代表公开HTTP恢复正确；重放必须保留原追问和输入字段，不能换成通用恢复提示。
            AgentResult replay=org.springframework.test.util.ReflectionTestUtils.invokeMethod(gateway,"completedReplay",scope.user,scope.session,
                fence,accepted.admission().get("sourceMessageId"),result);
            assertEquals("请说明尚未明确的目标",replay.message());
            assertEquals("query",replay.candidateCart().get("inputKey"));
            assertEquals("请说明尚未明确的目标",replay.candidateCart().get("question"));
            // 公开动作是input_required，query只属于输入字段；沿用实时协议，不创造新客户端动作。
            assertEquals("input_required",replay.nextAction());
        } else {
            // 原失败也通过同一公开映射恢复，不能丢失错误码或变成通用的任务状态回复。
            AgentResult replay=org.springframework.test.util.ReflectionTestUtils.invokeMethod(gateway,"completedReplay",scope.user,scope.session,
                fence,accepted.admission().get("sourceMessageId"),result);
            assertEquals(AgentResult.Status.FAILED,replay.status());
            assertEquals("合成规划拒绝",replay.message());
            assertEquals("VALIDATION_ERROR",replay.error());
        }
        assertEquals("completed",jdbc.queryForObject("SELECT state FROM agent_task_turns WHERE turn_id=?",String.class,accepted.turnId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions WHERE conversation_task_id=?",Integer.class,taskId));
    }

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
