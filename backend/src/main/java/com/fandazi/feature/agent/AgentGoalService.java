// GR-02目标事务服务：在同一Spring短事务中提交公开投影、三版本、请求回执和业务效果。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 不调用Agent、模型或领域HTTP；调用方在事务外完成候选保存和ACK传输。 */
@Service
public class AgentGoalService {
    private static final Set<String> REVISION_FIELDS=Set.of("schemaVersion","requestId","candidateHash","expectedGoalRevision",
            "expectedPlanRevision","expectedEffectVersion","goalRevision","planRevision","projection","taskFence");
    private static final Set<String> FENCE_FIELDS=Set.of("conversationTaskId","taskVersion","focusVersion","executionGeneration");
    private static final Set<String> SAFE_EFFECT_FIELDS=Set.of("orderId","refundRequestId","complaintTicketId","status");
    private final AgentTaskRegistryService registry;private final AgentTaskRepository tasks;private final AgentGoalRepository goals;
    private final AgentGoalRecoveryRepository recoveries;private final AgentGoalEffectRepository effects;private final AgentProtocolValidator contracts;
    private final ConfirmationGrantService grants;

    /** 注入同一JPA事务使用的仓储，并预编译2.3公开协议。 */
    public AgentGoalService(AgentTaskRegistryService registry,AgentTaskRepository tasks,AgentGoalRepository goals,
            AgentGoalRecoveryRepository recoveries,AgentGoalEffectRepository effects,ConfirmationGrantService grants,ObjectMapper mapper) {
        this.registry=registry;this.tasks=tasks;this.goals=goals;this.recoveries=recoveries;this.effects=effects;
        this.grants=grants;
        contracts=new AgentProtocolValidator(mapper);
    }

    /** 锁session→task→request，核任务围栏与三版本后原子提交公开目标版本。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> commitRevision(Long user,String sessionId,String taskId,Map<String,Object> request) {
        if(request==null||!request.keySet().equals(REVISION_FIELDS)||!"2.3".equals(request.get("schemaVersion")))throw AgentTaskRegistryService.bad("目标修订结构无效");
        String requestId=text(request,"requestId",100),candidateHash=text(request,"candidateHash",64);
        if(!candidateHash.matches("^[0-9a-f]{64}$"))throw AgentTaskRegistryService.bad("候选摘要无效");
        if(!(request.get("taskFence") instanceof Map<?,?> rawFence)||!rawFence.keySet().equals(FENCE_FIELDS))throw AgentTaskRegistryService.bad("目标任务围栏无效");
        Map<String,Object> fence=(Map<String,Object>)rawFence;
        if(!taskId.equals(fence.get("conversationTaskId")))throw AgentTaskRegistryService.conflict("目标任务身份冲突");
        long expectedGoal=version(request,"expectedGoalRevision",1),expectedPlan=version(request,"expectedPlanRevision",1),expectedEffect=version(request,"expectedEffectVersion",0);
        long nextGoal=version(request,"goalRevision",1),nextPlan=version(request,"planRevision",1);
        if(!(request.get("projection") instanceof Map<?,?> rawProjection))throw AgentTaskRegistryService.bad("目标投影无效");
        Map<String,Object> projection=new LinkedHashMap<>();rawProjection.forEach((key,value)->projection.put(String.valueOf(key),value));
        contracts.validateGoalPayload("goalProjection",projection);
        String fingerprint=AgentTaskFingerprint.of(request);

        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);var task=registry.lockTask(scope,taskId);
        if(!taskId.equals(session.currentTaskId()))throw AgentTaskRegistryService.conflict("目标任务不是当前焦点");
        if(!"2.3".equals(task.protocolVersion()))throw AgentTaskRegistryService.conflict("任务未启用目标协议");
        if(task.version()!=version(fence,"taskVersion",1)||task.generation()!=version(fence,"executionGeneration",1)
                ||session.focusVersion()!=version(fence,"focusVersion",0))throw AgentTaskRegistryService.conflict("目标任务围栏已变化");
        String requestKey=AgentTaskFingerprint.of(List.of(scope.key(),requestId));
        var previous=recoveries.lockByRequestKey(requestKey).orElse(null);
        if(previous!=null) {
            if(!previous.taskId().equals(taskId)||!previous.fingerprint().equals(fingerprint))throw AgentTaskRegistryService.conflict("同目标请求正文冲突");
            return previous.receipt();
        }
        if(task.goalRevision()!=expectedGoal||task.planRevision()!=expectedPlan||task.effectVersion()!=expectedEffect)
            throw AgentTaskRegistryService.conflict("目标版本已变化");
        if(!taskId.equals(projection.get("conversationTaskId"))||version(projection,"taskVersion",1)!=task.version()
                ||version(projection,"goalRevision",1)!=nextGoal||version(projection,"planRevision",1)!=nextPlan
                ||version(projection,"effectVersion",0)!=expectedEffect)throw AgentTaskRegistryService.conflict("公开投影版本不符");
        List<Map<String,Object>> outcomes=new ArrayList<>();
        for(Object item:(List<?>)projection.get("outcomes")) {
            if(!(item instanceof Map<?,?> raw))throw AgentTaskRegistryService.bad("目标结果无效");
            var outcome=new LinkedHashMap<String,Object>();raw.forEach((key,value)->outcome.put(String.valueOf(key),value));outcomes.add(outcome);
        }
        List<String> goalIds=outcomes.stream().map(value->String.valueOf(value.get("goalId"))).toList();
        if(goalIds.size()!=new HashSet<>(goalIds).size())throw AgentTaskRegistryService.bad("目标身份重复");
        var receipt=new LinkedHashMap<String,Object>();receipt.put("schemaVersion","2.3");receipt.put("requestId",requestId);
        receipt.put("state","committed");receipt.put("action","replan_remaining");receipt.put("goalIds",goalIds);
        receipt.put("goalRevision",nextGoal);receipt.put("planRevision",nextPlan);receipt.put("effectVersion",expectedEffect);
        receipt.put("allowedActions",projection.get("availableActions"));receipt.put("candidateHash",candidateHash);
        try {task.commitGoalRevision(expectedGoal,expectedPlan,expectedEffect,nextGoal,nextPlan,projection,requestId);}
        catch(ToolBoundaryException conflict){throw AgentTaskRegistryService.conflict(conflict.getMessage());}
        for(Map<String,Object> outcome:outcomes)goals.save(new AgentGoalEntity(taskId,nextGoal,nextPlan,expectedEffect,outcome));
        recoveries.save(new AgentGoalRecoveryEntity(requestKey,requestId,scope,taskId,fingerprint,candidateHash,
                expectedGoal,expectedPlan,expectedEffect,nextGoal,nextPlan,expectedEffect,receipt));
        // 目标/计划变化在同一事务撤销本任务旧确认与未消费grant；修订许可本身不能消费或签发业务授权。
        // 即使旧工具请求随后被三版本围栏挡住，授权记录也必须明确失效，不能留给其他入口误用。
        grants.revokeTasks(user,sessionId,Set.of(taskId));
        session.clearTaskConfirmation(Set.of(taskId));tasks.save(task);return receipt;
    }

    /** 只读返回当前公开投影；身份不符与不存在统一404，空投影不伪造目标完成。 */
    @Transactional(readOnly=true)
    public Map<String,Object> projection(Long user,String sessionId,String taskId) {
        var scope=registry.scope(user,sessionId);var task=tasks.findById(taskId).orElseThrow(AgentTaskRegistryService::missing);
        if(!task.ownedBy(scope))throw AgentTaskRegistryService.missing();return task.goalProjection();
    }

    /** 在既有turn完成事务中保存同版本目标结果；调用方已持session/task锁并先写入本轮停点。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> commitTurnProjection(AgentTaskEntity task,Map<String,Object> payload) {
        contracts.validateGoalPayload("goalOutcomePayload",payload);
        if(!"2.3".equals(task.protocolVersion())||task.goalProjection()==null)throw AgentTaskRegistryService.conflict("任务没有活动目标投影");
        List<Map<String,Object>> outcomes=new ArrayList<>();
        for(Object item:(List<?>)payload.get("outcomes")){
            if(!(item instanceof Map<?,?> raw))throw AgentTaskRegistryService.bad("目标结果无效");
            var outcome=new LinkedHashMap<String,Object>();raw.forEach((key,value)->outcome.put(String.valueOf(key),value));outcomes.add(outcome);
        }
        var current=task.goalProjection();
        Set<String> expectedIds=new LinkedHashSet<>();for(Object item:(List<?>)current.get("outcomes"))expectedIds.add(String.valueOf(((Map<?,?>)item).get("goalId")));
        Set<String> actualIds=new LinkedHashSet<>();for(var outcome:outcomes){
            actualIds.add(String.valueOf(outcome.get("goalId")));
            if(version(outcome,"goalRevision",1)!=task.goalRevision()||version(outcome,"planRevision",1)!=task.planRevision()
                    ||version(outcome,"effectVersion",0)!=task.effectVersion())throw AgentTaskRegistryService.conflict("目标结果版本已变化");
        }
        if(actualIds.size()!=outcomes.size()||!actualIds.equals(expectedIds))throw AgentTaskRegistryService.conflict("目标结果身份不一致");
        var actions=new LinkedHashSet<String>();for(var outcome:outcomes)for(Object action:(List<?>)outcome.get("allowedActions"))actions.add(String.valueOf(action));
        var projection=new LinkedHashMap<String,Object>();projection.put("schemaVersion","2.3");projection.put("conversationTaskId",task.id());
        projection.put("taskVersion",task.version());projection.put("goalRevision",task.goalRevision());projection.put("planRevision",task.planRevision());
        projection.put("effectVersion",task.effectVersion());projection.put("aggregateStatus",payload.get("aggregateStatus"));projection.put("outcomes",outcomes);
        projection.put("availableActions",new ArrayList<>(actions));contracts.validateGoalPayload("goalProjection",projection);
        var rows=goals.findByTaskIdAndGoalRevision(task.id(),task.goalRevision());var byId=new HashMap<String,AgentGoalEntity>();for(var row:rows)byId.put(row.goalId(),row);
        if(!byId.keySet().equals(actualIds))throw AgentTaskRegistryService.conflict("目标历史行与当前结果不一致");
        for(var outcome:outcomes)byId.get(String.valueOf(outcome.get("goalId"))).updateOutcome(task.planRevision(),task.effectVersion(),outcome);
        task.commitGoalOutcomes(projection);goals.saveAll(byId.values());tasks.save(task);return projection;
    }

    /** 按可信scope生成请求键并回读原回执；不能用裸requestId枚举其他账号。 */
    @Transactional(readOnly=true)
    public Map<String,Object> revision(Long user,String sessionId,String taskId,String requestId) {
        var scope=registry.scope(user,sessionId);String key=AgentTaskFingerprint.of(List.of(scope.key(),requestId));
        var row=recoveries.findByRequestKey(key).orElseThrow(AgentTaskRegistryService::missing);
        // 内部修订回查与公开恢复请求共用持久表但不共用语义；公开accepted行不能冒充候选修订ACK。
        if(!row.taskId().equals(taskId)||!"revision".equals(row.action()))throw AgentTaskRegistryService.missing();
        return row.receipt();
    }

    /** 保存真实业务安全回执并推进effectVersion；重复同体不再次递增，异体冲突。 */
    @Transactional
    public Map<String,Object> commitBusinessEffect(Long user,String sessionId,String taskId,String effectKey,
            long expectedGoalRevision,long expectedPlanRevision,long expectedEffectVersion,Map<String,Object> receipt) {
        // 效果键直接复用工具幂等键，长度必须与工具执行表的255字符上限一致，避免长runId在业务已成功后无法登记效果。
        if(effectKey==null||effectKey.isBlank()||effectKey.length()>255||receipt==null||!SAFE_EFFECT_FIELDS.containsAll(receipt.keySet())
                ||receipt.isEmpty())throw AgentTaskRegistryService.bad("业务效果安全摘要无效");
        String fingerprint=AgentTaskFingerprint.of(receipt);
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);var task=registry.lockTask(scope,taskId);
        var previous=effects.lockByTaskAndKey(taskId,effectKey).orElse(null);
        if(previous!=null) {
            if(!previous.fingerprint().equals(fingerprint))throw AgentTaskRegistryService.conflict("同业务效果键正文冲突");
            return Map.of("effectVersion",previous.effectVersion(),"duplicate",true);
        }
        long committed;
        try {committed=task.commitBusinessEffect(expectedGoalRevision,expectedPlanRevision,expectedEffectVersion);}
        catch(ToolBoundaryException conflict){throw AgentTaskRegistryService.conflict(conflict.getMessage());}
        effects.save(new AgentGoalEffectEntity(taskId,effectKey,expectedGoalRevision,expectedPlanRevision,committed,fingerprint,receipt));
        tasks.save(task);return Map.of("effectVersion",committed,"duplicate",false);
    }

    /** 读取必需非空字符串；错误消息不包含原始字段值。 */
    private static String text(Map<String,Object> value,String field,int max) {
        Object raw=value.get(field);if(!(raw instanceof String text)||text.isBlank()||text.length()>max)throw AgentTaskRegistryService.bad("目标字段无效："+field);return text;
    }
    /** 读取整数版本并拒绝布尔、负数和小数；minimum按协议字段分别传入。 */
    private static long version(Map<?,?> value,String field,long minimum) {
        Object raw=value.get(field);if(!(raw instanceof Number number)||raw instanceof Double||raw instanceof Float||number.longValue()<minimum)
            throw AgentTaskRegistryService.bad("目标版本无效："+field);return number.longValue();
    }
}
