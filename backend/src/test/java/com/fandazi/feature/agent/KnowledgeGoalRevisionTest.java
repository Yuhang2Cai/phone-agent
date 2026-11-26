package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreRepository;
import com.fandazi.feature.support.KnowledgeCatalog;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** 多目标修订对知识交付的组件反例；使用真实任务/目标/工具/目录事务，不冒称模型规划或在线验收。 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ag08-goal-knowledge;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","seed.enabled=false","group.seed.enabled=false",
    "agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false","agent.goal-recovery.enabled=true"})
@Transactional
class KnowledgeGoalRevisionTest {
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired KnowledgeCatalog catalog;@Autowired UserRepository users;@Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;@Autowired AgentTaskToolBridge tools;
    @Autowired AgentTaskFenceService fences;@Autowired JdbcTemplate jdbc;@Autowired StoreRepository stores;
    @Autowired DealPackageRepository deals;@Autowired AgentGoalService goals;@Autowired AgentEventService events;

    /** 已提交写是不可撤销的历史事实；恢复后的新知识可引用它，但缺失原效果回执时必须失败关闭。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"intact","missing","wrong-object","wrong-fingerprint","future-effect"})
    void committed_write_survives_generation_change_only_with_original_effect(String receiptCase) {
        String id=UUID.randomUUID().toString();
        catalog.publish(id,"合成部分恢复",List.of(new KnowledgeCatalog.Entry(id,"1","合成规则","恢复规则","仅模拟交易。",Instant.EPOCH,null,List.of("恢复规则"))));
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);
        entityManager.flush();
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",flow.scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",flow.taskId);
        entityManager.clear();
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,revision(flow,1,List.of("goal-faq","goal-order")));
        String run=(String)flow.fence().get("runId");
        var goalFence=Map.<String,Object>of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-order"));
        // 已建单前的套餐详情只服务原确认，不是恢复回答正文；旧详情不得阻断原订单事实交付。
        tools.execute("get_deal_detail",new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,
            "detail",run+":detail:1",1,Map.of("dealId",flow.dealId),flow.fence(),goalFence));
        var quote=tools.execute("create_purchase_quote",new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,
            "quote",run+":quote:1",1,Map.of("dealId",flow.dealId,"quantity",2),flow.fence(),goalFence));
        flow.awaitAndConfirm(quote);
        tools.execute("create_group_buy_order",new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,
            "order",run+":order:1",1,Map.of("quoteId",quote.get("quoteId"),"confirmationGrantId",quote.get("confirmationGrantId")),flow.fence(),goalFence));
        var faqRequest=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,"faq",run+":faq:2",2,
            Map.of("question","恢复规则"),flow.fence(),Map.of("goalRevision",2,"planRevision",2,"effectVersion",1,"goalIds",List.of("goal-faq")));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)tools.execute("query_faq",faqRequest).get("faqs");
        // 只改变恢复代次条件；订单、原动作和效果均已由真实服务原子提交，不能用SQL伪造成功业务。
        entityManager.flush();
        jdbc.update("UPDATE agent_conversation_tasks SET execution_generation=2 WHERE conversation_task_id=?",flow.taskId);
        jdbc.update("UPDATE agent_tool_executions SET execution_generation=2 WHERE execution_key=?",faqRequest.idempotencyKey());
        // 负例只破坏实际提交后的回执，验证不能凭旧正文补造可信事实；测试事务结束全部回滚。
        if("missing".equals(receiptCase))jdbc.update("DELETE FROM agent_goal_effects WHERE conversation_task_id=?",flow.taskId);
        if("wrong-object".equals(receiptCase))jdbc.update("UPDATE agent_goal_effects SET receipt_json=? WHERE conversation_task_id=?","{\"orderId\":\"foreign-order\",\"status\":\"PENDING_PAYMENT\"}",flow.taskId);
        if("wrong-fingerprint".equals(receiptCase))jdbc.update("UPDATE agent_goal_effects SET fingerprint=? WHERE conversation_task_id=?","0".repeat(64),flow.taskId);
        if("future-effect".equals(receiptCase))jdbc.update("UPDATE agent_goal_effects SET effect_version=9 WHERE conversation_task_id=?",flow.taskId);
        entityManager.clear();
        String message="订单已创建，请确认是否进行模拟支付。\n"+faqs.get(0).get("answer");
        org.junit.jupiter.api.function.Executable deliver=()->events.record(flow.scope.user,flow.scope.session,"run_completed",message,null,"succeeded",
            Map.of("runId",run,"traceId",flow.trace(),"message",message));
        if("intact".equals(receiptCase))assertDoesNotThrow(deliver,"原已提交写应作为历史事实保留，不得为恢复交付再次执行");
        else assertThrows(IllegalStateException.class,deliver,"缺少原效果不得仅凭旧工具结果放行");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,flow.scope.user));
    }

    /** 恢复后旧动作只作审计：先选择各节点最新结果，再核验其代次；没有新读取仍须拒绝旧事实。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void obsolete_action_generation_does_not_poison_refreshed_node(boolean refreshed) {
        String id=UUID.randomUUID().toString();
        catalog.publish(id,"合成恢复代次",List.of(new KnowledgeCatalog.Entry(id,"1","合成规则","恢复规则","仅模拟交易。",Instant.EPOCH,null,List.of("恢复规则"))));
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);
        entityManager.flush();
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",flow.scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",flow.taskId);
        entityManager.clear();
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,revision(flow,1,List.of("goal-faq","goal-orders")));
        String run=(String)flow.fence().get("runId");
        var faqRequest=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,"faq",run+":faq:2",2,
            Map.of("question","恢复规则"),flow.fence(),Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-faq")));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)tools.execute("query_faq",faqRequest).get("faqs");
        for(int version=1;version<=(refreshed?2:1);version++) {
            var orders=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,"orders",run+":orders:"+version,version,
                Map.of(),flow.fence(),Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-orders")));
            tools.execute("list_user_orders",orders);
        }
        // 组件夹具只注入已真实读取结果的恢复代次条件；不伪造订单、来源、确认或成功回执。
        // 真正暂停/恢复已由线上r2执行，线上旧代次1/新代次3的失败原样保留。
        entityManager.flush();
        jdbc.update("UPDATE agent_conversation_tasks SET execution_generation=2 WHERE conversation_task_id=?",flow.taskId);
        jdbc.update("UPDATE agent_tool_executions SET execution_generation=2 WHERE execution_key IN (?,?)",run+":faq:2",run+":orders:2");
        entityManager.clear();
        String message="查询到 0 条订单记录\n"+faqs.get(0).get("answer");
        org.junit.jupiter.api.function.Executable deliver=()->events.record(flow.scope.user,flow.scope.session,"run_completed",message,null,"succeeded",
            Map.of("runId",run,"traceId",flow.trace(),"message",message));
        if(refreshed)assertDoesNotThrow(deliver,"已被新结果替代的旧动作不得阻断当前交付");
        else assertThrows(IllegalStateException.class,deliver,"没有新读取不能用旧代次结果冒充当前事实");
    }

    /** 原知识目标明确移除后，同run的业务回答不应再被旧知识节点强迫附加已取消内容。 */
    @Test void removing_knowledge_goal_does_not_force_old_answer_into_remaining_business() {
        String id=UUID.randomUUID().toString();
        catalog.publish(id,"合成目标知识修订",List.of(new KnowledgeCatalog.Entry(id,"1","合成规则","修订规则","仅模拟交易。",Instant.EPOCH,null,List.of("修订规则"))));
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);
        // 沿用既有AG04夹具的协议升级前置，后续修订与工具必须通过真实产品服务，不直接改来源/许可表。
        entityManager.flush();
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",flow.scope.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",flow.taskId);
        // JDBC升级后清除测试事务一级缓存，避免服务读到夹具创建时的旧协议实体。
        entityManager.clear();
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,revision(flow,1,List.of("goal-faq","goal-orders")));
        String run=(String)flow.fence().get("runId");
        var faq=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,"faq",run+":faq:1",1,
            Map.of("question","修订规则"),flow.fence(),Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-faq")));
        assertEquals(1,((List<?>)tools.execute("query_faq",faq).get("faqs")).size());
        goals.commitRevision(flow.scope.user,flow.scope.session,flow.taskId,revision(flow,2,List.of("goal-orders")));
        // 本测试外包回滚事务；同步目标实体后让交付当前读看到与真实分次HTTP提交一致的状态。
        entityManager.flush();
        var orders=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,"orders",run+":orders:1",1,
            Map.of(),flow.fence(),Map.of("goalRevision",3,"planRevision",3,"effectVersion",0,"goalIds",List.of("goal-orders")));
        assertEquals(List.of(),tools.execute("list_user_orders",orders).get("orders"));
        assertDoesNotThrow(()->events.record(flow.scope.user,flow.scope.session,"run_completed","查询到 0 条订单记录",null,"succeeded",
            Map.of("runId",run,"traceId",flow.trace(),"message","查询到 0 条订单记录")),"已取消的知识目标不应污染新计划交付");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=?",Integer.class,faq.idempotencyKey()),"不应给取消目标补造新知识许可");
        entityManager.flush();
        assertDoesNotThrow(()->events.after(flow.scope.user,flow.scope.session,null),"明确零知识来源的业务历史仍应可重放");
        // 同一个动作键不能在修订后被重新指派给另一目标，历史绑定不得被缓存回放偷换归属。
        var reassigned=new InternalV2ToolRequest("2.1",String.valueOf(flow.scope.user),flow.scope.session,flow.trace(),run,"faq",faq.idempotencyKey(),1,
            Map.of("question","修订规则"),flow.fence(),Map.of("goalRevision",3,"planRevision",3,"effectVersion",0,"goalIds",List.of("goal-orders")));
        assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,()->tools.execute("query_faq",reassigned)).getCode());
        // 取消不是回答放行开关：仍须拒绝没有任何账本支持的规则文字。
        assertThrows(IllegalStateException.class,()->events.record(flow.scope.user,flow.scope.session,"run_completed","支持真实支付",null,"succeeded",
            Map.of("runId",run,"traceId",flow.trace(),"message","支持真实支付")));
    }

    /** 生成严格目标修订信封；版本来自真实围栏，目标集合是本例冻结的明确修订输入。 */
    private Map<String,Object> revision(AgentTaskToolTestFixtures flow,long expected,List<String> goalIds) {
        long next=expected+1;var fence=flow.fence();long taskVersion=((Number)fence.get("taskVersion")).longValue();
        var outcomes=new ArrayList<Map<String,Object>>();
        for(String id:goalIds) {
            var outcome=new LinkedHashMap<String,Object>();outcome.put("goalId",id);outcome.put("description","goal-faq".equals(id)?"了解合成规则":"查询本人订单");
            outcome.put("status","pending");outcome.put("reasonCode","awaiting_execution");outcome.put("resourceSummary",null);
            outcome.put("completedConditionLabels",List.of());outcome.put("missingConditionLabels",List.of("query_completed"));outcome.put("allowedActions",List.of());
            outcome.put("goalRevision",next);outcome.put("planRevision",next);outcome.put("effectVersion",0);outcomes.add(outcome);
        }
        var projection=Map.<String,Object>of("schemaVersion","2.3","conversationTaskId",flow.taskId,"taskVersion",taskVersion,
            "goalRevision",next,"planRevision",next,"effectVersion",0,"aggregateStatus","partial","outcomes",outcomes,"availableActions",List.of());
        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion","2.3");request.put("requestId",UUID.randomUUID().toString());
        request.put("candidateHash",AgentTaskFingerprint.of(projection));request.put("expectedGoalRevision",expected);request.put("expectedPlanRevision",expected);
        request.put("expectedEffectVersion",0);request.put("goalRevision",next);request.put("planRevision",next);request.put("projection",projection);
        request.put("taskFence",Map.of("conversationTaskId",flow.taskId,"taskVersion",taskVersion,"focusVersion",fence.get("focusVersion"),"executionGeneration",fence.get("executionGeneration")));
        return request;
    }
}
