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
