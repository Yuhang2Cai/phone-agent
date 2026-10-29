// 工具验收合成夹具：真实套餐/报价/授权/普通轮次，不能直接SQL伪造已提交订单或有效grant。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 测试金额1234分、数量2，预期订单2468分；固定值防止默认数量/模型金额偷换。 */
final class AgentTaskToolTestFixtures {
    final AgentTaskTestFixtures scope;final String taskId,dealId;final AgentTaskRegistryService registry;
    final AgentTaskTurnService turns;final AgentTaskToolBridge tools;final AgentTaskFenceService fences;final JdbcTemplate jdbc;
    final AgentSessionService sessions;
    final String intent;
    AgentTaskTurnService.Accepted turn;
    /** 通过真实仓储建立独立合成对象，生命周期仍经产品服务，不直接伪造任务/凭证。 */
    AgentTaskToolTestFixtures(UserRepository users,AgentSessionService sessions,AgentTaskRegistryService registry,AgentTaskTurnService turns,
            AgentTaskToolBridge tools,AgentTaskFenceService fences,JdbcTemplate jdbc,StoreRepository stores,DealPackageRepository deals){
        this(users,sessions,registry,turns,tools,fences,jdbc,stores,deals,"transaction");
    }
    /** 复合任务同样通过真实目录创建；仅改变任务类别，业务、确认和订单仍由原服务执行。 */
    AgentTaskToolTestFixtures(UserRepository users,AgentSessionService sessions,AgentTaskRegistryService registry,AgentTaskTurnService turns,
            AgentTaskToolBridge tools,AgentTaskFenceService fences,JdbcTemplate jdbc,StoreRepository stores,DealPackageRepository deals,String kind){
        scope=new AgentTaskTestFixtures(users,sessions,registry,jdbc);this.registry=registry;this.turns=turns;this.tools=tools;this.fences=fences;this.jdbc=jdbc;this.sessions=sessions;
        intent="composite".equals(kind)?"composite":"group_buy_order";
        taskId=AgentTaskTestFixtures.id(registry.create(scope.user,scope.session,kind,intent,"合成购买"));String suffix=UUID.randomUUID().toString();
        var store=stores.saveAndFlush(new StoreEntity("ag03-store-"+suffix,"合成品牌","合成门店","310000","合成地址","合成电话","中餐",new BigDecimal("31.2"),new BigDecimal("121.4"),1234,true,"09:00-22:00",null));
        dealId="ag03-deal-"+suffix;deals.saveAndFlush(new DealPackageEntity(dealId,store,"合成双份套餐","合成测试",1234,2000,20,LocalDate.now().minusDays(1),LocalDate.now().plusDays(20),true,5,7));
        admit("合成购买输入");
    }
    /** 同task新输入用新turn，等待确认后不换run/代次；版本只从权威目录读取。 */
    void admit(String body){var task=AgentTaskTestFixtures.current(scope.directory());turn=turns.admitTurn(scope.user,scope.session,
        new AgentTaskTurnService.Input(UUID.randomUUID().toString(),body,taskId,((Number)task.get("taskVersion")).longValue(),((Number)scope.directory().get("focusVersion")).longValue(),true));}
    /** 取得由Spring签发的私有栅栏，不从期望值伪造身份。 */
    @SuppressWarnings("unchecked") Map<String,Object> fence(){return (Map<String,Object>)turn.admission().get("fence");}
    /** 同一输入trace从持久轮次取得，防止沿用旧输入链路获得确认。 */
    String trace(){return jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId());}
    /** 构造可信执行器动作键；测试不允许通过payload输入价格或任务身份。 */
    InternalV2ToolRequest request(String node,int actionVersion,Map<String,Object> payload){String run=(String)fence().get("runId");return new InternalV2ToolRequest("2.0",String.valueOf(scope.user),scope.session,trace(),run,node,run+":"+node+":"+actionVersion,actionVersion,payload,fence());}
    /** 准备真实报价，仅签发确认；是否购买由后续用户确认与写工具决定。 */
    Map<String,Object> quote(int version){return tools.execute("create_purchase_quote",request("quote",version,Map.of("dealId",dealId,"quantity",2)));}
    /** 实际完成等待确认结果，释放普通租约，并通过新输入明确确认原展示摘要。 */
    Map<String,Object> awaitAndConfirm(Map<String,Object> quote){
        var summary=Map.<String,Object>of("action","create_group_buy_order","resourceId",quote.get("quoteId"),"title","合成购买确认","amountCents",quote.get("totalCents"),
            "ruleVersion",quote.get("confirmationRuleVersion"),"dealTitle","合成双份套餐","quantity",2);
        awaitConfirmation(summary);
        // TL-06出口会重查并规范标题/金额/规则/期限；确认必须使用实际展示摘要，不能继续回传模型原始结构。
        var displayed=sessions.owned(scope.user,scope.session).getPendingConfirmation();
        admit("合成用户明确确认");fences.confirmCurrent(fence(),trace(),displayed);return displayed;
    }
    /** 保存合法等待确认事件，不直接改confirmed字段或预置有效凭证。 */
    void awaitConfirmation(Map<String,Object> summary){
        var context=new LinkedHashMap<>(fence());context.remove("scope");context.remove("runId");
        var payload=Map.of("summary",summary,"actions",List.of(Map.of("actionId","synthetic-confirm","actionType",summary.get("action"),"label","确认","resourceId",summary.get("resourceId"),"enabled",true)));
        var event=Map.<String,Object>of("schemaVersion","2.2","eventId",UUID.randomUUID().toString(),"eventType","confirmation_required","traceId",trace(),"runId",fence().get("runId"),"sequence",0,
            "status","waiting_confirmation","payload",payload,"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.2","traceId",trace(),"runId",fence().get("runId"),"status","waiting_confirmation","intent",intent,"events",List.of(event));
        turns.complete(scope.user,scope.session,Map.of("schemaVersion","2.2","turnId",turn.turnId(),"fence",fence(),"sourceMessageId",turn.admission().get("sourceMessageId"),"result",result));
    }
    /** 原报价/原grant参与购买，领域恢复真实数量/金额，禁止以测试SQL创建订单。 */
    InternalV2ToolRequest creation(Map<String,Object> quote){return request("create-order",1,Map.of("quoteId",quote.get("quoteId"),"confirmationGrantId",quote.get("confirmationGrantId")));}
}
