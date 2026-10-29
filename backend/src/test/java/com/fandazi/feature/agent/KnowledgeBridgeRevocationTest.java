package com.fandazi.feature.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.StoreRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.support.KnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** 真实工具桥缓存反例：查询已经成功并持久化后，撤回必须阻止V1和Task原键再交付。 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ag08-bridge;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate","seed.enabled=false","group.seed.enabled=false",
        "agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false"})
@Transactional
class KnowledgeBridgeRevocationTest {
    @Autowired KnowledgeCatalog catalog; @Autowired AgentV1ToolBridge v1;
    @Autowired AgentTaskToolBridge taskTools; @Autowired AgentSessionService sessions; @Autowired UserRepository users;
    @Autowired AgentTaskRegistryService registry; @Autowired AgentTaskTurnService turns; @Autowired AgentTaskFenceService fences;
    @Autowired JdbcTemplate jdbc; @Autowired StoreRepository stores; @Autowired DealPackageRepository deals;
    @Autowired AgentMessageService messages;
    @Autowired AgentEventService events;
    @Autowired AgentHistoryService history;
    @Autowired AgentMessageWriter messageWriter;
    @Autowired AgentTaskGatewayService taskGateway;

    /** 混合门店与规则回答只在原工具未提供位置时允许固定提示，不能借提示豁免知识正文校验。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void location_notice_must_match_recorded_store_query(boolean hasCity) {
        publish();
        var flow=new AgentTaskToolTestFixtures(users,sessions,registry,turns,taskTools,fences,jdbc,stores,deals);
        var found=taskTools.execute("search_stores",flow.request("stores",1,hasCity?Map.of("cityCode","310100"):Map.of()));
        var faq=flow.request("faq",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)taskTools.execute("query_faq",faq).get("faqs");
        String message="未获取定位，门店结果不按距离排序。\n查询到 "+((List<?>)found.get("stores")).size()+" 条门店记录\n"+rows.get(0).get("answer");
        org.junit.jupiter.api.function.Executable deliver=()->events.record(flow.scope.user,flow.scope.session,"run_completed",message,null,"succeeded",Map.of("runId",faq.runId(),"message",message));
        if(hasCity)assertThrows(IllegalStateException.class,deliver,"有城市范围时不能冒称没有位置输入");
        else assertDoesNotThrow(deliver,"无定位目录查询应保留真实限定说明");
    }

    /** 工具审计不是知识最终交付；新写入及旧审计重放均不得携带未经交付许可的原始FAQ数组。 */
    @Test void public_tool_audit_does_not_leak_raw_knowledge_before_delivery() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kx-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kx-"+id,AgentMode.CONFIRM,"trace-"+id);
        String secretBody="合成审核正文不能从工具审计直接发出";
        var result=Map.<String,Object>of("ok",true,"data",Map.of("faqs",List.of(Map.of("id",id,"question","合成问题","answer",secretBody))));
        var payload=Map.<String,Object>of("runId",session.getActiveRunId(),"result",result);
        var fresh=events.record(user,session.getSessionId(),"tool_result","工具执行结果","query_faq","succeeded",payload);
        assertFalse(fresh.getPayload().toString().contains(secretBody),"原始FAQ不能持久为公开工具审计");
        // 模拟升级前已存在的原始审计行，只核对真实公开DTO转换，不删除或改写旧数据库证据。
        var old=new AgentEventEntity(user,session.getSessionId(),"tool_result","工具执行结果","query_faq","succeeded",payload);
        assertFalse(AgentGatewayController.EventResponse.from(old).payload().toString().contains(secretBody),"旧审计也必须在公开出口剔除原文");
    }

    /** 当前回答使用同节点最新读取，历史仍按原交付事实展示，不能把不同条件的数组合并计数。 */
    @Test void changed_business_filter_uses_latest_result_without_rewriting_history() {
        publish();
        var fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,taskTools,fences,jdbc,stores,deals);
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);
        taskTools.execute("create_group_buy_order",fixture.creation(quote));
        taskTools.execute("list_user_orders",fixture.request("orders",1,Map.of()));
        var request=fixture.request("faq",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)taskTools.execute("query_faq",request).get("faqs");
        String first="查询到 1 条订单记录\n"+faqs.get(0).get("answer");
        events.record(fixture.scope.user,fixture.scope.session,"run_completed",first,null,"succeeded",Map.of("runId",request.runId(),"message",first));
        var newer=taskTools.execute("list_user_orders",fixture.request("orders",2,Map.of("status","CANCELLED")));
        assertEquals(List.of(),newer.get("orders"),"真实未支付订单不能被取消筛选命中");
        String second="查询到 0 条订单记录\n"+faqs.get(0).get("answer");
        assertDoesNotThrow(()->events.record(fixture.scope.user,fixture.scope.session,"run_completed",second,null,"succeeded",Map.of("runId",request.runId(),"message",second)),"新条件不能混入旧查询对象");
        var replay=assertDoesNotThrow(()->events.after(fixture.scope.user,fixture.scope.session,null));
        assertTrue(replay.stream().anyMatch(row->first.equals(row.getPayload().get("message"))),"历史显示当时已授权事实，不能改写成新查询结果");
        assertTrue(replay.stream().anyMatch(row->second.equals(row.getPayload().get("message"))));
    }

    /** 没有run与来源身份的旧助手正文无法判定是否仍为有效规则，不能靠文字看起来普通而放行。 */
    @Test void untyped_legacy_assistant_history_is_not_assumed_safe() {
        String id=UUID.randomUUID().toString();
        Long user=users.saveAndFlush(new UserEntity("ku-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"ku-"+id,AgentMode.CONFIRM,"trace-"+id);
        messageWriter.write(user,session.getSessionId(),null,"legacy-"+id,"assistant","合成旧规则正文，缺少原运行身份",null);
        assertThrows(IllegalStateException.class,()->history.page(user,session.getSessionId(),null,100));
    }

    /** 无run的合法控制短回复由持久无工具完成回执证明，不能因知识升级使这类记录无法恢复。 */
    @Test void persisted_control_reply_without_run_remains_readable() {
        var fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var turn=turns.admitTurn(fixture.user,fixture.session,new AgentTaskTurnService.Input(UUID.randomUUID().toString(),"合成问候",null,0,0,false));
        turns.completeReply(fixture.user,fixture.session,turn.turnId(),"合成控制已完成");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId());
        messageWriter.write(fixture.user,fixture.session,null,trace,"assistant","合成控制已完成",null);
        assertDoesNotThrow(()->history.page(fixture.user,fixture.session,null,100));
    }

    /** 订单契约使用orderId而非publicId；真实购买后组合规则查询不能被错误身份字段拒绝。 */
    @Test void mixed_faq_and_owned_orders_uses_canonical_order_identity() {
        publish();
        var fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,taskTools,fences,jdbc,stores,deals);
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);
        taskTools.execute("create_group_buy_order",fixture.creation(quote));
        var orders=taskTools.execute("list_user_orders",fixture.request("orders",1,Map.of()));
        assertEquals(1,((List<?>)orders.get("orders")).size());
        var request=fixture.request("faq",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)taskTools.execute("query_faq",request).get("faqs");
        String answer="查询到 1 条订单记录\n"+faqs.get(0).get("answer");
        assertDoesNotThrow(()->events.record(fixture.scope.user,fixture.scope.session,"run_completed",answer,null,"succeeded",Map.of("runId",request.runId(),"message",answer)));
        String forged="查询到 2 条订单记录\n"+faqs.get(0).get("answer");
        assertThrows(IllegalStateException.class,()->events.record(fixture.scope.user,fixture.scope.session,"run_completed",forged,null,"succeeded",Map.of("runId",request.runId(),"message",forged)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user),"知识校验不得重发购买");
    }

    /** 升级前V1账本未写run列；复合任务的旧FAQ不能因无绑定且意图不是faq而被当普通正文放行。 */
    @Test void legacy_composite_faq_without_binding_fails_closed() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kl-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kl-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var request=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)v1.execute("query_faq",request).get("faqs");
        // 仅在回滚测试事务中模拟旧版本已存在的缺列账本，不修改生产资料或把它当迁移成功。
        jdbc.update("DELETE FROM knowledge_bindings WHERE execution_key=?",request.idempotencyKey());
        jdbc.update("UPDATE agent_tool_executions SET run_id=NULL WHERE execution_key=?",request.idempotencyKey());
        events.record(user,session.getSessionId(),"intent_decided","合成复合意图",null,"running",Map.of("intent","composite","runId",run));
        assertThrows(IllegalStateException.class,()->events.record(user,session.getSessionId(),"run_completed",faqs.get(0).get("answer"),null,"succeeded",Map.of("runId",run,"message",faqs.get(0).get("answer"))),"旧复合FAQ不能借普通正文路径交付");
    }

    /** 已完成任务的HTTP重试走私有完成回执，必须与普通历史一样重新检查原交付来源。 */
    @Test void completed_task_http_replay_cannot_bypass_revocation() {
        String id=publish();
        var fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,taskTools,fences,jdbc,stores,deals);
        var tool=fixture.request("faq",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)taskTools.execute("query_faq",tool).get("faqs");
        String answer=faqs.get(0).get("answer");
        var context=new java.util.LinkedHashMap<>(fixture.fence());context.remove("scope");context.remove("runId");
        var event=Map.<String,Object>of("schemaVersion","2.2","eventId",UUID.randomUUID().toString(),"eventType","run_completed",
            "runId",tool.runId(),"traceId",tool.traceId(),"sequence",0,"status","succeeded","payload",Map.of("message",answer),"taskContext",context);
        messages.record(new AgentRunRequest(tool.userId(),tool.sessionId(),"缓存规则","confirm",tool.traceId(),tool.runId(),Map.of(),"2.2"),event);
        var result=Map.<String,Object>of("status","succeeded","events",List.of(event));
        // 直接调用真实网关的完成回放分支以隔离归属模型，仍使用真实任务围栏、工具与数据库许可。
        assertDoesNotThrow(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(taskGateway,"completedReplay",fixture.scope.user,fixture.scope.session,fixture.fence(),fixture.turn.admission().get("sourceMessageId"),result));
        catalog.revoke(id,"1","完成回执已缓存之后撤回");
        assertThrows(IllegalStateException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(taskGateway,"completedReplay",fixture.scope.user,fixture.scope.session,fixture.fence(),fixture.turn.admission().get("sourceMessageId"),result));
    }

    /** 同run后续追问不能把早先消息错误绑定到最新动作；历史按原交付关联重验且仍受撤回约束。 */
    @Test void old_history_keeps_its_original_action_after_a_later_question() {
        String id=UUID.randomUUID().toString();
        catalog.publish(id,"合成历史动作测试",List.of(
            new KnowledgeCatalog.Entry(id+"a","1","规则甲","历史甲","甲仅模拟交易。",Instant.EPOCH,null,List.of("历史甲")),
            new KnowledgeCatalog.Entry(id+"b","1","规则乙","历史乙","乙不转人工。",Instant.EPOCH,null,List.of("历史乙"))));
        Long user=users.saveAndFlush(new UserEntity("ka-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"ka-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var first=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","历史甲"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)v1.execute("query_faq",first).get("faqs");
        String answer=rows.get(0).get("answer");
        var original=events.record(user,session.getSessionId(),"run_completed",answer,null,"succeeded",Map.of("runId",run,"traceId",session.getTraceId(),"message",answer));
        messageWriter.write(user,session.getSessionId(),run,session.getTraceId(),"assistant",answer,null);
        v1.execute("query_faq",new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:2",2,Map.of("question","历史乙")));
        assertDoesNotThrow(()->history.page(user,session.getSessionId(),null,100),"同一run的新查询不能替换原消息的来源集合");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE delivery_ref=? AND execution_key=?",Integer.class,original.getKnowledgeDeliveryRef(),run+":faq:1")>0);
        catalog.revoke(id+"a","1","旧动作来源单独撤回");
        assertThrows(IllegalStateException.class,()->history.page(user,session.getSessionId(),null,100),"不能拿仍有效的新动作替代已撤回旧动作");
    }

    /** 消息页可以先于事件页返回正文，不能只保护SSE而漏掉助手历史消息。 */
    @Test void paged_assistant_history_must_recheck_revocation() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kh-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kh-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var tool=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)v1.execute("query_faq",tool).get("faqs");
        String answer=faqs.get(0).get("answer");
        events.record(user,session.getSessionId(),"run_completed",answer,null,"succeeded",Map.of("runId",run,"traceId",session.getTraceId(),"message",answer));
        messageWriter.write(user,session.getSessionId(),run,session.getTraceId(),"assistant",answer,null);
        assertDoesNotThrow(()->history.page(user,session.getSessionId(),null,1));
        catalog.revoke(id,"1","分页读取前撤回");
        assertThrows(IllegalStateException.class,()->history.page(user,session.getSessionId(),null,1),"消息优先页不能绕过知识许可");
    }

    /** 没有消息行时事件页同样要受控，防止仅给消息加门控后仍从事件正文绕回。 */
    @Test void paged_event_history_must_recheck_revocation() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("ke-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"ke-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var tool=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var faqs=(List<Map<String,String>>)v1.execute("query_faq",tool).get("faqs");
        String answer=faqs.get(0).get("answer");
        events.record(user,session.getSessionId(),"run_completed",answer,null,"succeeded",Map.of("runId",run,"traceId",session.getTraceId(),"message",answer));
        catalog.revoke(id,"1","事件页前撤回");
        assertThrows(IllegalStateException.class,()->history.page(user,session.getSessionId(),null,100));
    }

    /** 空知识引发的澄清是未完成状态，不应要求不存在的答案来源或使历史永久不可用。 */
    @Test void clarification_history_is_not_mistaken_for_a_grounded_answer() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kc-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kc-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        v1.execute("query_faq",new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","未收录问题")));
        String clarification="请补充需要了解的平台规则";
        events.record(user,session.getSessionId(),"input_required",clarification,null,"waiting_input",Map.of("runId",run,"traceId",session.getTraceId(),"question",clarification));
        messageWriter.write(user,session.getSessionId(),run,session.getTraceId(),"assistant",clarification,null);
        assertDoesNotThrow(()->history.page(user,session.getSessionId(),null,100));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=?",Integer.class,run+":faq:1"));
    }

    /** 同一知识节点补充问题后的新动作替代旧空结果，旧正文仍不能借新动作取得许可。 */
    @Test void newer_knowledge_action_replaces_empty_binding_without_erasing_history() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kv-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kv-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var first=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","不存在的知识"));
        assertEquals(List.of(),v1.execute("query_faq",first).get("faqs"));
        var second=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:2",2,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)v1.execute("query_faq",second).get("faqs");
        var request=new AgentRunRequest(user.toString(),session.getSessionId(),"缓存规则","confirm",session.getTraceId(),run,Map.of());
        var event=Map.<String,Object>of("schemaVersion","2.0","eventId",UUID.randomUUID().toString(),"eventType","run_completed",
            "runId",run,"traceId",session.getTraceId(),"sequence",0,"status","succeeded","payload",Map.of("message",rows.get(0).get("answer")));
        assertDoesNotThrow(()->messages.record(request,event),"新的完整证据不应永久受旧空绑定阻塞");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bindings WHERE run_id=?",Integer.class,run),"历史绑定不能被删除");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=?",Integer.class,run+":faq:1"));
    }

    /** 组合查询允许账本证实的业务事实，但不能放行伪造计数或以其替代知识原文。 */
    @Test void mixed_answer_accepts_only_the_recorded_business_count() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("km-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"km-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var faq=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)v1.execute("query_faq",faq).get("faqs");
        var coupon=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"coupon",run+":coupon:1",1,Map.of());
        assertEquals(List.of(),v1.execute("query_coupon",coupon).get("coupons"));
        var request=new AgentRunRequest(user.toString(),session.getSessionId(),"规则并查优惠券","confirm",session.getTraceId(),run,Map.of());
        var event=new java.util.LinkedHashMap<String,Object>(Map.of("schemaVersion","2.0","eventId",UUID.randomUUID().toString(),"eventType","run_completed",
                "runId",run,"traceId",session.getTraceId(),"sequence",0,"status","succeeded"));
        event.put("payload",Map.of("message","查询到 0 条优惠券记录\n"+rows.get(0).get("answer")));
        assertDoesNotThrow(()->messages.record(request,event),"已核实的业务事实不能被知识门控一律拒绝");
        event.put("payload",Map.of("message","查询到 9 条优惠券记录\n"+rows.get(0).get("answer")));
        assertThrows(IllegalStateException.class,()->messages.record(request,event),"真实空列表不授权虚构9条");
    }

    /** 正向交付和历史撤回使用同一正文，防止仅靠一律拒绝让安全反例虚假通过。 */
    @Test void valid_answer_is_delivered_but_revoked_history_is_not_replayed() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kr-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kr-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var tool=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)v1.execute("query_faq",tool).get("faqs");
        String answer=rows.get(0).get("answer");
        var request=new AgentRunRequest(user.toString(),session.getSessionId(),"缓存规则","confirm",session.getTraceId(),run,Map.of());
        var event=Map.<String,Object>of("schemaVersion","2.0","eventId",UUID.randomUUID().toString(),"eventType","run_completed",
                "runId",run,"traceId",session.getTraceId(),"sequence",0,"status","succeeded","payload",Map.of("message",answer));
        assertDoesNotThrow(()->messages.record(request,event));
        assertTrue(events.after(user,session.getSessionId(),null).stream().anyMatch(value->answer.equals(value.getPayload().get("message"))));
        catalog.revoke(id,"1","交付成功后撤回，重连须重新许可");
        assertThrows(IllegalStateException.class,()->events.after(user,session.getSessionId(),null),"历史正文不能继承撤回前许可");
    }

    /** 仅测试知识使用新ID和版本；不撤回随包真实审核主题，不触碰其它会话。 */
    private String publish() {
        String id=UUID.randomUUID().toString();
        catalog.publish(id,"合成缓存回放测试",List.of(new KnowledgeCatalog.Entry(id,"1","合成规则","缓存规则",
                "仅模拟交易，不承诺到账。",Instant.EPOCH,null,List.of("缓存规则"))));
        return id;
    }
    @Test void v1_cached_faq_cannot_bypass_revocation() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kb-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kb-"+id,AgentMode.CONFIRM,"trace-"+id);
        String run=session.getActiveRunId();
        var request=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        var result=assertDoesNotThrow(()->v1.execute("query_faq",request));
        assertEquals(1,((List<?>)result.get("faqs")).size());
        assertEquals(result,v1.execute("query_faq",request));
        catalog.revoke(id,"1","原查询之后撤回");
        assertThrows(RuntimeException.class,()->v1.execute("query_faq",request),"原键不能绕过撤回校验");
    }
    @Test void task_cached_faq_cannot_bypass_revocation() {
        String id=publish();
        var fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,taskTools,fences,jdbc,stores,deals);
        var request=fixture.request("faq",1,Map.of("question","缓存规则"));
        var result=assertDoesNotThrow(()->taskTools.execute("query_faq",request));
        assertEquals(1,((List<?>)result.get("faqs")).size());
        assertEquals(result,taskTools.execute("query_faq",request));
        catalog.revoke(id,"1","Task原查询之后撤回");
        assertThrows(RuntimeException.class,()->taskTools.execute("query_faq",request),"任务缓存不能绕过知识当前读");
    }
    @Test void final_delivery_after_revocation_is_not_swallowed_as_optional_audit() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("kg-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"kg-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var tool=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        var data=v1.execute("query_faq",tool);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)data.get("faqs");
        String answer=rows.get(0).get("answer");
        var request=new AgentRunRequest(user.toString(),session.getSessionId(),"缓存规则","confirm",session.getTraceId(),run,Map.of());
        var event=Map.<String,Object>of("schemaVersion","2.0","eventId",UUID.randomUUID().toString(),"eventType","run_completed",
                "runId",run,"traceId",session.getTraceId(),"sequence",0,"status","succeeded","payload",Map.of("message",answer));
        catalog.revoke(id,"1","工具完成后、最终事件前撤回");
        assertThrows(RuntimeException.class,()->messages.record(request,event),"知识许可失败不能被审计catch吞掉");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_events WHERE user_id=? AND session_id=? AND type='run_completed'",Integer.class,user,session.getSessionId()));
    }
    @Test void approved_quote_does_not_authorize_an_appended_fund_promise() {
        String id=publish();
        Long user=users.saveAndFlush(new UserEntity("ki-"+id,id+"@example.invalid","synthetic-hash")).getId();
        var session=sessions.start(user,"ki-"+id,AgentMode.CONFIRM,"trace-"+id);String run=session.getActiveRunId();
        var tool=new InternalV1ToolRequest("1.1",user.toString(),session.getSessionId(),session.getTraceId(),run,"faq",run+":faq:1",1,Map.of("question","缓存规则"));
        var data=v1.execute("query_faq",tool);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,String>>)data.get("faqs");
        var request=new AgentRunRequest(user.toString(),session.getSessionId(),"缓存规则","confirm",session.getTraceId(),run,Map.of());
        var event=Map.<String,Object>of("schemaVersion","2.0","eventId",UUID.randomUUID().toString(),"eventType","run_completed",
                "runId",run,"traceId",session.getTraceId(),"sequence",0,"status","succeeded","payload",Map.of("message",rows.get(0).get("answer")+"\n保证一小时内真实到账。"));
        assertThrows(RuntimeException.class,()->messages.record(request,event),"存在合法引用不能授权额外资金承诺");
    }
}
