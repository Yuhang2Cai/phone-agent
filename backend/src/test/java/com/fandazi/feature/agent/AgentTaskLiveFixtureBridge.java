// TL-08真实进程夹具桥：仅位于测试classpath，建立合成前置并调用真实短事务服务，不替换产品实现。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreRepository;
import com.fandazi.feature.catalog.StoreEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 给故障驱动提供精确持久边界；不伪造ACK、订单、grant或成功计数，不进入生产JAR。 */
public final class AgentTaskLiveFixtureBridge {
    private final ApplicationContext context;
    /** 依赖真实Spring容器，所有事务仍通过容器代理执行。 */
    public AgentTaskLiveFixtureBridge(ApplicationContext context){this.context=context;}
    /** 读取真实组件，不在测试桥内复制领域逻辑或创建替身。 */
    private <T>T bean(Class<T> type){return context.getBean(type);}

    /** 复用产品包内JWT校验器，固定测试驱动主体；不把内部校验类改成公开生产API。 */
    public void authorize(String authorization){bean(InternalV1JwtValidator.class).validate(authorization,"910000");}

    /** 构造GR-04两目标真实查询前置；目标修订、轮次和目录均通过产品事务创建，夹具只返回合成公开身份。 */
    private Map<String,Object> seedGoalPartial(){
        var users=bean(UserRepository.class);var sessions=bean(AgentSessionService.class);var registry=bean(AgentTaskRegistryService.class);
        var jdbc=bean(JdbcTemplate.class);var f=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var task=registry.create(f.user,f.session,"composite","composite","合成门店推荐与详情查询");String taskId=AgentTaskTestFixtures.id(task);
        // 生产开关接入属于后续任务；本地真实链路只把新建且尚无投影的合成任务固定为2.3，旧任务不迁移。
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",f.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);

        // 两个真实目录对象分别供成功搜索与故障详情分支使用，避免用不存在对象制造业务失败冒充依赖故障。
        var stores=bean(StoreRepository.class);var first=stores.saveAndFlush(new StoreEntity("gr04-store-a-"+UUID.randomUUID(),"合成品牌甲","GR04门店甲","310000",
            "合成地址甲","021-10000001","中餐",new BigDecimal("31.20"),new BigDecimal("121.40"),880,true,"09:00-22:00",null));
        var second=stores.saveAndFlush(new StoreEntity("gr04-store-b-"+UUID.randomUUID(),"合成品牌乙","GR04门店乙","310000",
            "合成地址乙","021-10000002","火锅",new BigDecimal("31.21"),new BigDecimal("121.41"),920,true,"09:00-22:00",null));

        long taskVersion=((Number)task.get("taskVersion")).longValue();long focus=((Number)f.directory().get("focusVersion")).longValue();
        var firstOutcome=pendingOutcome("goal-search","查询真实门店候选",List.of("candidate_found"));
        var secondOutcome=pendingOutcome("goal-detail","查询指定门店电话",List.of("phone"));
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",taskId);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","partial");projection.put("outcomes",List.of(firstOutcome,secondOutcome));projection.put("availableActions",List.of());
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");revision.put("requestId","gr04-initial-"+UUID.randomUUID());
        revision.put("candidateHash","a".repeat(64));revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);
        revision.put("expectedEffectVersion",0);revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",taskVersion,"focusVersion",focus,
            "executionGeneration",task.get("executionGeneration")));
        bean(AgentGoalService.class).commitRevision(f.user,f.session,taskId,revision);

        // 修订提交后再受理执行轮次，许可必须固化2.3及当前任务版本；后续完成用该原许可做幂等补交。
        var current=AgentTaskTestFixtures.current(f.directory());String query="查询门店候选，并读取指定门店电话";
        var accepted=bean(AgentTaskTurnService.class).admitTurn(f.user,f.session,new AgentTaskTurnService.Input("gr04-turn-"+UUID.randomUUID(),query,taskId,
            ((Number)current.get("taskVersion")).longValue(),((Number)f.directory().get("focusVersion")).longValue(),true));
        var result=new LinkedHashMap<String,Object>();result.put("userId",String.valueOf(f.user));result.put("sessionId",f.session);
        result.put("conversationEpoch",f.epoch);result.put("taskId",taskId);result.put("query",query);result.put("admission",accepted.admission());
        result.put("goalContext",Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-search","goal-detail")));
        result.put("storeIds",List.of(first.getPublicId(),second.getPublicId()));return result;
    }

    /** 建立2.3真实建单链路：目标版本、报价、展示确认和当前确认轮次均由产品服务持久化。 */
    private Map<String,Object> seedGoalWrite(){
        var users=bean(UserRepository.class);var sessions=bean(AgentSessionService.class);var registry=bean(AgentTaskRegistryService.class);
        var turns=bean(AgentTaskTurnService.class);var tools=bean(AgentTaskToolBridge.class);var fences=bean(AgentTaskFenceService.class);
        var jdbc=bean(JdbcTemplate.class);var purchase=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,
            bean(StoreRepository.class),bean(DealPackageRepository.class));var f=purchase.scope;
        // 仅升级本夹具新建的合成任务和原准备轮次；目标版本仍通过真实短事务提交，不能直接SQL伪造投影或业务效果。
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",f.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",purchase.taskId);
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",purchase.turn.turnId());
        var current=AgentTaskTestFixtures.current(f.directory());long taskVersion=((Number)current.get("taskVersion")).longValue();
        long focus=((Number)f.directory().get("focusVersion")).longValue();var initial=pendingOutcome("goal-order","创建唯一团购订单",List.of("order_created"));
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",purchase.taskId);
        projection.put("taskVersion",taskVersion);projection.put("goalRevision",2);projection.put("planRevision",2);projection.put("effectVersion",0);
        projection.put("aggregateStatus","partial");projection.put("outcomes",List.of(initial));projection.put("availableActions",List.of());
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");revision.put("requestId","gr05-write-"+UUID.randomUUID());
        revision.put("candidateHash","c".repeat(64));revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);
        revision.put("expectedEffectVersion",0);revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",purchase.taskId,"taskVersion",taskVersion,"focusVersion",focus,
            "executionGeneration",current.get("executionGeneration")));bean(AgentGoalService.class).commitRevision(f.user,f.session,purchase.taskId,revision);

        // 报价仍由真实工具创建；随后用2.3完成事务保存“等待确认”目标和实际展示摘要，再由新轮次明确确认。
        var quote=purchase.quote(1);var summary=Map.<String,Object>of("action","create_group_buy_order","resourceId",quote.get("quoteId"),
            "title","合成购买确认","amountCents",quote.get("totalCents"),"ruleVersion",quote.get("confirmationRuleVersion"),
            "dealTitle","合成双份套餐","quantity",2);var fence=purchase.fence();var taskContext=new LinkedHashMap<>(fence);
        taskContext.remove("scope");taskContext.remove("runId");var waiting=pendingOutcome("goal-order","创建唯一团购订单",List.of("order_created"));
        // 公开目标动作只能使用2.3冻结枚举；业务“确认”仍由独立 confirmation_required 事件表达。
        waiting.put("status","waiting_confirmation");waiting.put("reasonCode","awaiting_confirmation");waiting.put("allowedActions",List.of("reprepare_remaining"));
        var goalEvent=Map.<String,Object>of("schemaVersion","2.3","eventId","gr05-goal-"+UUID.randomUUID(),"eventType","goal_outcomes",
            "traceId",purchase.trace(),"runId",fence.get("runId"),"sequence",0,"status","waiting_confirmation",
            "payload",Map.of("aggregateStatus","waiting_confirmation","outcomes",List.of(waiting)),"taskContext",taskContext);
        var confirmationEvent=Map.<String,Object>of("schemaVersion","2.3","eventId","gr05-confirm-"+UUID.randomUUID(),"eventType","confirmation_required",
            "traceId",purchase.trace(),"runId",fence.get("runId"),"sequence",1,"status","waiting_confirmation",
            "payload",Map.of("summary",summary,"actions",List.of(Map.of("actionId","gr05-confirm","actionType","create_group_buy_order",
                "label","确认","resourceId",quote.get("quoteId"),"enabled",true))),"taskContext",taskContext);
        var runResult=Map.<String,Object>of("schemaVersion","2.3","traceId",purchase.trace(),"runId",fence.get("runId"),
            "status","waiting_confirmation","intent","group_buy_order","events",List.of(goalEvent,confirmationEvent));
        turns.complete(f.user,f.session,Map.of("schemaVersion","2.3","turnId",purchase.turn.turnId(),"fence",fence,
            "sourceMessageId",purchase.turn.admission().get("sourceMessageId"),"result",runResult));
        var displayed=sessions.owned(f.user,f.session).getPendingConfirmation();purchase.admit("合成用户明确确认建单");fences.confirmCurrent(purchase.fence(),purchase.trace(),displayed);

        var result=new LinkedHashMap<String,Object>();result.put("userId",String.valueOf(f.user));result.put("sessionId",f.session);
        result.put("conversationEpoch",f.epoch);result.put("taskId",purchase.taskId);result.put("traceId",purchase.trace());
        result.put("admission",purchase.turn.admission());result.put("goalContext",Map.of("goalRevision",2,"planRevision",2,"effectVersion",0,"goalIds",List.of("goal-order")));
        result.put("creation",purchase.creation(quote));result.put("quoteId",quote.get("quoteId"));return result;
    }

    /** 建立真实待支付原订单和支付确认停点；只把当前恢复轮次改为2.3，供两服务调用重准备入口。 */
    private Map<String,Object> seedGoalPayment(){
        var purchase=new AgentTaskToolTestFixtures(bean(UserRepository.class),bean(AgentSessionService.class),bean(AgentTaskRegistryService.class),
            bean(AgentTaskTurnService.class),bean(AgentTaskToolBridge.class),bean(AgentTaskFenceService.class),bean(JdbcTemplate.class),
            bean(StoreRepository.class),bean(DealPackageRepository.class));var quote=purchase.quote(1);purchase.awaitAndConfirm(quote);
        var order=bean(AgentTaskToolBridge.class).execute("create_group_buy_order",purchase.creation(quote));String orderId=(String)order.get("orderId");
        purchase.awaitConfirmation(Map.of("action","pay_mock_order","resourceId",orderId,"title","合成支付确认","amountCents",order.get("totalCents"),"ruleVersion","pay-v1"));
        purchase.admit("合成恢复原订单支付");bean(JdbcTemplate.class).update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",purchase.turn.turnId());
        var result=new LinkedHashMap<String,Object>();result.put("userId",String.valueOf(purchase.scope.user));result.put("sessionId",purchase.scope.session);
        result.put("conversationEpoch",purchase.scope.epoch);result.put("taskId",purchase.taskId);result.put("traceId",purchase.trace());
        result.put("admission",purchase.turn.admission());result.put("orderId",orderId);result.put("oldPaymentGrantId",order.get("paymentConfirmationGrantId"));return result;
    }

    /** 建立可受理只读恢复的2.3投影；双Agent只能竞争同一真实任务行，夹具不预建恢复记录。 */
    private Map<String,Object> seedGoalRecovery(){
        var f=new AgentTaskTestFixtures(bean(UserRepository.class),bean(AgentSessionService.class),bean(AgentTaskRegistryService.class),bean(JdbcTemplate.class));
        var task=f.selection();String taskId=AgentTaskTestFixtures.id(task);var jdbc=bean(JdbcTemplate.class);
        jdbc.update("UPDATE agent_sessions SET context_protocol_version='2.3' WHERE session_id=?",f.session);
        jdbc.update("UPDATE agent_conversation_tasks SET protocol_version='2.3' WHERE conversation_task_id=?",taskId);
        var outcome=pendingOutcome("goal-read","恢复真实只读查询",List.of("fresh_evidence"));
        outcome.put("status","recoverable");outcome.put("reasonCode","read_temporarily_failed");outcome.put("allowedActions",List.of("retry_reads"));
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",taskId);
        projection.put("taskVersion",task.get("taskVersion"));projection.put("goalRevision",2);projection.put("planRevision",2);
        projection.put("effectVersion",0);projection.put("aggregateStatus","recoverable");projection.put("outcomes",List.of(outcome));
        projection.put("availableActions",List.of("retry_reads"));
        var revision=new LinkedHashMap<String,Object>();revision.put("schemaVersion","2.3");revision.put("requestId","gr08-initial-"+UUID.randomUUID());
        revision.put("candidateHash","d".repeat(64));revision.put("expectedGoalRevision",1);revision.put("expectedPlanRevision",1);
        revision.put("expectedEffectVersion",0);revision.put("goalRevision",2);revision.put("planRevision",2);revision.put("projection",projection);
        revision.put("taskFence",Map.of("conversationTaskId",taskId,"taskVersion",task.get("taskVersion"),
            "focusVersion",f.directory().get("focusVersion"),"executionGeneration",task.get("executionGeneration")));
        bean(AgentGoalService.class).commitRevision(f.user,f.session,taskId,revision);
        return new LinkedHashMap<>(Map.of("userId",String.valueOf(f.user),"sessionId",f.session,"conversationEpoch",f.epoch,
            "taskId",taskId,"directory",f.directory(),"projection",projection));
    }

    /**
     * 为四类写动作建立各自独立的真实前置和待发送原请求。
     * 前置订单、支付、准备与确认都走产品服务；目标写本身留给HTTP故障驱动执行，便于在事务提交后丢响应。
     */
    private Map<String,Object> seedGoalWriteCase(String toolName){
        if(!Set.of("create_group_buy_order","pay_mock_order","request_refund","submit_complaint").contains(toolName))
            throw new IllegalArgumentException("不支持的四写动作");
        var purchase=new AgentTaskToolTestFixtures(bean(UserRepository.class),bean(AgentSessionService.class),bean(AgentTaskRegistryService.class),
            bean(AgentTaskTurnService.class),bean(AgentTaskToolBridge.class),bean(AgentTaskFenceService.class),bean(JdbcTemplate.class),
            bean(StoreRepository.class),bean(DealPackageRepository.class));var tools=bean(AgentTaskToolBridge.class);
        var committedKeys=new ArrayList<String>();var quote=purchase.quote(1);purchase.awaitAndConfirm(quote);
        InternalV2ToolRequest request=purchase.creation(quote);
        Map<String,Object> order=null;
        if(!"create_group_buy_order".equals(toolName)){
            order=tools.execute("create_group_buy_order",request);
            committedKeys.add(request.idempotencyKey());
            confirm(purchase,Map.of("action","pay_mock_order","resourceId",order.get("orderId"),"title","确认原订单支付",
                "amountCents",order.get("totalCents"),"ruleVersion","pay-v1"),"合成用户确认支付");
            request=purchase.request("gr08-pay",1,Map.of("orderId",order.get("orderId"),
                "confirmationGrantId",order.get("paymentConfirmationGrantId")));
        }
        if(Set.of("request_refund","submit_complaint").contains(toolName)){
            tools.execute("pay_mock_order",request);
            committedKeys.add(request.idempotencyKey());
            if("request_refund".equals(toolName)){
                String voucher=(String)((Map<?,?>)((List<?>)order.get("vouchers")).get(0)).get("publicId");
                var prepared=tools.execute("prepare_refund",purchase.request("gr08-refund-prepare",1,Map.of("voucherId",voucher)));
                confirm(purchase,Map.of("action","request_refund","resourceId",voucher,"title","确认退款申请",
                    "amountCents",prepared.get("amountCents"),"dealTitle",prepared.get("dealTitle"),"reason","行程取消","ruleVersion","refund-v1"),
                    "合成用户确认退款");
                request=purchase.request("gr08-refund",1,Map.of("voucherId",voucher,"reason","行程取消",
                    "confirmationGrantId",prepared.get("confirmationGrantId")));
            }else{
                var prepared=tools.execute("prepare_complaint",purchase.request("gr08-complaint-prepare",1,Map.of("orderId",order.get("orderId"))));
                confirm(purchase,Map.of("action","submit_complaint","resourceId",order.get("orderId"),"title","确认投诉",
                    "amountCents",0,"ruleVersion","complaint-v1","subject","服务问题","description","希望核实服务流程"),
                    "合成用户确认投诉");
                request=purchase.request("gr08-complaint",1,Map.of("orderId",order.get("orderId"),"subject","服务问题",
                    "description","希望核实服务流程","confirmationGrantId",prepared.get("confirmationGrantId")));
            }
        }
        bean(JdbcTemplate.class).update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",request.taskFence().get("turnId"));
        var result=new LinkedHashMap<String,Object>();result.put("userId",String.valueOf(purchase.scope.user));result.put("sessionId",purchase.scope.session);
        result.put("conversationEpoch",purchase.scope.epoch);result.put("taskId",purchase.taskId);result.put("toolName",toolName);
        result.put("request",wire(request));result.put("executionKey",request.idempotencyKey());
        result.put("committedExecutionKeys",committedKeys);
        if(order!=null)result.put("orderId",order.get("orderId"));return result;
    }

    /** 完成真实等待确认停点，再用新轮次确认同一服务端展示摘要，不直接修改 grant 或 confirmed 字段。 */
    private void confirm(AgentTaskToolTestFixtures purchase,Map<String,Object> summary,String body){
        purchase.awaitConfirmation(summary);var displayed=bean(AgentSessionService.class).owned(purchase.scope.user,purchase.scope.session).getPendingConfirmation();
        purchase.admit(body);bean(AgentTaskFenceService.class).confirmCurrent(purchase.fence(),purchase.trace(),displayed);
    }

    /** 把Java请求记录转换为实际HTTP正文；2.0请求删除空goalFence，避免夹具引入协议外字段。 */
    @SuppressWarnings("unchecked")
    private Map<String,Object> wire(InternalV2ToolRequest request){
        var value=new LinkedHashMap<String,Object>(bean(com.fasterxml.jackson.databind.ObjectMapper.class).convertValue(request,Map.class));
        if(value.get("goalFence")==null)value.remove("goalFence");return value;
    }

    /** 创建满足2.3公开Schema的初始目标行；最终状态只能由真实工具证据覆盖。 */
    private Map<String,Object> pendingOutcome(String goalId,String description,List<String> missing){
        var value=new LinkedHashMap<String,Object>();value.put("goalId",goalId);value.put("description",description);value.put("status","pending");
        value.put("reasonCode","awaiting_execution");value.put("resourceSummary",null);value.put("completedConditionLabels",List.of());
        value.put("missingConditionLabels",missing);value.put("allowedActions",List.of());value.put("goalRevision",2);value.put("planRevision",2);
        value.put("effectVersion",0);return value;
    }

    /** 合成身份与任务只由产品服务创建；购买前置由现有真实报价/确认夹具建立。 */
    public Map<String,Object> seed(String kind){
        if("goal-partial".equals(kind))return seedGoalPartial();
        if("goal-write".equals(kind))return seedGoalWrite();
        if("goal-payment".equals(kind))return seedGoalPayment();
        if("goal-recovery".equals(kind))return seedGoalRecovery();
        if(kind!=null&&kind.startsWith("goal-write:"))return seedGoalWriteCase(kind.substring("goal-write:".length()));
        var users=bean(UserRepository.class);var sessions=bean(AgentSessionService.class);var registry=bean(AgentTaskRegistryService.class);
        var jdbc=bean(JdbcTemplate.class);var result=new LinkedHashMap<String,Object>();AgentTaskTestFixtures f;
        if("purchase".equals(kind)){
            var purchase=new AgentTaskToolTestFixtures(users,sessions,registry,bean(AgentTaskTurnService.class),bean(AgentTaskToolBridge.class),
                bean(AgentTaskFenceService.class),jdbc,bean(StoreRepository.class),bean(DealPackageRepository.class));
            var quote=purchase.quote(1);purchase.awaitAndConfirm(quote);f=purchase.scope;
            // 私有原执行信封只交给本地驱动，日志和安全汇总不得输出grant或完整载荷。
            result.put("creation",purchase.creation(quote));result.put("admission",purchase.turn.admission());
            result.put("quote",quote);result.put("summary",sessions.owned(f.user,f.session).getPendingConfirmation());
            result.put("traceId",purchase.trace());result.put("query","合成用户明确确认");
        }else{
            f=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
            if("switch".equals(kind))result.put("pausedTaskId",f.seedPaused());
            if("read".equals(kind)){
                // 慢只读场景的门店是真实目录对象；不使用工具响应替身或前端设计样例。
                var store=bean(StoreRepository.class).saveAndFlush(new StoreEntity("tl08-store-"+UUID.randomUUID(),"合成品牌","故障验收门店","310000",
                    "合成地址","合成电话","中餐",new BigDecimal("31.2"),new BigDecimal("121.4"),1234,true,"09:00-22:00",null));
                result.put("storeId",store.getPublicId());
                result.put("currentTaskId",AgentTaskTestFixtures.id(registry.create(f.user,f.session,"lookup","store_info","合成门店查询")));
            }else result.put("currentTaskId",AgentTaskTestFixtures.id(f.selection()));
        }
        result.put("userId",String.valueOf(f.user));result.put("sessionId",f.session);result.put("conversationEpoch",f.epoch);
        result.put("directory",f.directory());return result;
    }

    /** 以已经创建的scope调用产品事务；非法操作明确失败，不把异常转成成功结果。 */
    @SuppressWarnings("unchecked")
    public Object call(Map<String,Object> raw){
        String op=(String)raw.get("op");if("seed".equals(op))return seed((String)raw.get("kind"));
        if("database".equals(op))return bean(JdbcTemplate.class).queryForMap("SELECT DATABASE() AS database_name, VERSION() AS database_version");
        Long user=Long.valueOf((String)raw.get("userId"));String session=(String)raw.get("sessionId");
        var controls=bean(AgentTaskControlService.class);String id=(String)raw.get("commandId");
        // 每个入口仅执行一个真实短事务，让驱动在事务返回之后阻断HTTP或终止进程。
        return switch(op){
            case "directory" -> bean(AgentTaskRegistryService.class).directory(user,session);
            case "newTask" -> bean(AgentTaskRegistryService.class).create(user,session,"selection","deal_recommendation","合成新的前台任务");
            case "events" -> bean(AgentTaskEventOutbox.class).read(user,session,((Number)raw.getOrDefault("after",0)).longValue(),100);
            case "goalProjection" -> bean(AgentGoalRecoveryService.class).projection(user,session,(String)raw.get("taskId"));
            case "goalRecovery" -> bean(AgentGoalRecoveryService.class).accept(user,session,(String)raw.get("taskId"),(Map<String,Object>)raw.get("request"));
            case "goalRecoveryRead" -> bean(AgentGoalRecoveryService.class).read(user,session,(String)raw.get("taskId"),(String)raw.get("requestId"));
            case "goalRevision" -> bean(AgentGoalService.class).commitRevision(user,session,(String)raw.get("taskId"),(Map<String,Object>)raw.get("request"));
            case "goalRevisionRead" -> bean(AgentGoalService.class).revision(user,session,(String)raw.get("taskId"),(String)raw.get("requestId"));
            case "prepare" -> controls.prepare(user,session,(Map<String,Object>)raw.get("request"));
            case "claim" -> controls.claim(user,session,id,(String)raw.get("owner"),Instant.now());
            case "ack" -> controls.acknowledge(user,session,id,(Map<String,Object>)raw.get("ack"));
            case "commit" -> controls.commit(user,session,id);
            case "abort" -> controls.beginAbort(user,session,id);
            case "process" -> bean(AgentTaskReconciliationService.class).process(id,Instant.now());
            case "recheck" -> bean(AgentTaskReconciliationService.class).recheck(user,session,id);
            case "read" -> controls.read(user,session,id);
            case "admit" -> {
                var input=(Map<String,Object>)raw.get("input");
                yield bean(AgentTaskTurnService.class).admitTurn(user,session,new AgentTaskTurnService.Input((String)input.get("requestId"),
                    (String)input.get("body"),(String)input.get("taskId"),((Number)input.get("taskVersion")).longValue(),
                    ((Number)input.get("focusVersion")).longValue(),Boolean.TRUE.equals(input.get("executable"))));
            }
            case "deliver" -> {
                // 使用真实outbox认领/发送/确认；消费方只收公开事件，不执行业务工具。
                var delivered=new ArrayList<Map<String,Object>>();bean(AgentTaskEventDispatcher.class).deliverOnce(delivered::add);yield delivered;
            }
            default -> throw new IllegalArgumentException("未知测试边界操作");
        };
    }
}
