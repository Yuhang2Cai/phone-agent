// AG04公开恢复服务：在可信账号、任务和三版本内幂等受理用户动作，并支持原请求只读回查。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本服务不调用Agent、模型或业务工具，也不签发确认凭证。
 * 它只把用户明确动作固化为一次可审计受理，后续执行链必须再次核对任务围栏和业务确认。
 */
@Service
public class AgentGoalRecoveryService {
    private static final Set<String> REQUEST_FIELDS=Set.of("schemaVersion","requestId","goalIds","action",
        "expectedGoalRevision","expectedPlanRevision","expectedEffectVersion");
    private static final Set<String> ACTIONS=Set.of("retry_reads","reprepare_remaining","replan_remaining",
        "recheck_original","drop_remaining");
    private final AgentTaskRegistryService registry;
    private final AgentTaskRepository tasks;
    private final AgentGoalRecoveryRepository recoveries;
    private final AgentConversationSourceService sources;
    private final AgentProtocolValidator contracts;
    private final boolean enabled;

    /** 注入同库短事务依赖；功能开关只允许配置开启，HTTP正文不能改变。 */
    public AgentGoalRecoveryService(AgentTaskRegistryService registry,AgentTaskRepository tasks,
            AgentGoalRecoveryRepository recoveries,AgentConversationSourceService sources,ObjectMapper mapper,
            @Value("${agent.goal-recovery.enabled:false}") boolean enabled) {
        this.registry=registry;this.tasks=tasks;this.recoveries=recoveries;this.sources=sources;
        this.enabled=enabled;contracts=new AgentProtocolValidator(mapper);
    }

    /**
     * 返回本人当前公开目标投影。
     * 关闭恢复写开关时仍允许读取既有2.3历史；旧任务和空投影不会被伪造成空目标成功。
     */
    @Transactional(readOnly=true)
    public Map<String,Object> projection(Long user,String sessionId,String taskId) {
        var scope=registry.scope(user,sessionId);
        var task=tasks.findById(taskId).orElseThrow(AgentTaskRegistryService::missing);
        if(!task.ownedBy(scope))throw AgentTaskRegistryService.missing();
        var projection=task.goalProjection();
        if(!"2.3".equals(task.protocolVersion())||projection==null)
            throw AgentTaskRegistryService.conflict("任务没有可恢复目标投影");
        return projection;
    }

    /**
     * 按 session→task→request 的统一锁序受理恢复动作。
     * 同requestId同体返回原回执，异体冲突；受理过程中不建立业务轮次、不调用模型或写工具。
     */
    @Transactional
    public Map<String,Object> accept(Long user,String sessionId,String taskId,Map<String,Object> request) {
        validateRequest(request);
        if(!enabled)throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"目标恢复动作暂未启用");
        String requestId=(String)request.get("requestId"),action=(String)request.get("action");
        String fingerprint=AgentTaskFingerprint.of(request);

        // 输入校验后先锁会话和所属任务；跨用户、旧epoch与不存在统一404，避免枚举资源。
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);
        var task=registry.lockTask(scope,taskId);
        String requestKey=AgentTaskFingerprint.of(List.of(scope.key(),requestId));
        // 同一scope已由session行锁串行化；普通读取避免给不同会话的不存在requestKey施加MySQL范围锁。
        var previous=recoveries.findByRequestKey(requestKey).orElse(null);
        if(previous!=null) {
            if(!previous.taskId().equals(taskId)||!previous.fingerprint().equals(fingerprint)||"revision".equals(previous.action()))
                throw AgentTaskRegistryService.conflict("同目标恢复请求正文冲突");
            return previous.receipt();
        }

        // 只有当前前台2.3任务可受理新动作；暂停、切换、取消或交接中的任务不能继续恢复。
        if(!"2.3".equals(task.protocolVersion())||!"active".equals(task.lifecycle())
                ||!taskId.equals(session.currentTaskId())||task.pendingCommand()!=null)
            throw AgentTaskRegistryService.conflict("目标任务当前不可恢复");
        long expectedGoal=version(request,"expectedGoalRevision",1);
        long expectedPlan=version(request,"expectedPlanRevision",1);
        long expectedEffect=version(request,"expectedEffectVersion",0);
        if(task.goalRevision()!=expectedGoal||task.planRevision()!=expectedPlan||task.effectVersion()!=expectedEffect)
            throw AgentTaskRegistryService.conflict("目标恢复版本已变化");
        // task行锁把跨进程检查串行化；同一三版本只能有一条accepted动作，换requestId不能并行开新恢复轮次。
        if(!recoveries.findAccepted(taskId,expectedGoal,expectedPlan,expectedEffect).isEmpty())
            throw AgentTaskRegistryService.conflict("已有目标恢复请求等待处理或核查");

        var projection=task.goalProjection();
        if(projection==null)throw AgentTaskRegistryService.conflict("目标投影尚未建立");
        List<String> goalIds=strings(request.get("goalIds"));
        Set<String> available=new LinkedHashSet<>(strings(projection.get("availableActions")));
        if(!available.contains(action))throw AgentTaskRegistryService.conflict("目标恢复动作当前不可用");

        // 顶层动作可用仍不足以授权具体目标；每个目标都必须存在且单独声明同一动作。
        Map<String,Map<String,Object>> outcomes=new LinkedHashMap<>();
        Object rawOutcomes=projection.get("outcomes");
        if(!(rawOutcomes instanceof List<?> rows))throw AgentTaskRegistryService.conflict("目标投影结构无效");
        boolean hasUnknown=false;
        for(Object item:rows) {
            if(!(item instanceof Map<?,?> raw))throw AgentTaskRegistryService.conflict("目标投影结构无效");
            var outcome=new LinkedHashMap<String,Object>();raw.forEach((key,value)->outcome.put(String.valueOf(key),value));
            String id=String.valueOf(outcome.get("goalId"));outcomes.put(id,outcome);
            if("unknown_outcome".equals(outcome.get("status")))hasUnknown=true;
        }
        for(String goalId:goalIds) {
            var outcome=outcomes.get(goalId);
            if(outcome==null||!strings(outcome.get("allowedActions")).contains(action))
                throw AgentTaskRegistryService.conflict("目标恢复动作与目标不匹配");
        }
        // 任一未知写存在时整任务只能核查原执行身份，不能借另一目标按钮发起重试或重新准备。
        if(hasUnknown!="recheck_original".equals(action))
            throw AgentTaskRegistryService.conflict("写入结果未知时只允许核查原请求");

        // 来源正文是服务端规范动作和目标，不接收客户端伪造的成功、工具或确认字段；同ID重试复用同来源。
        String sourceBody="目标恢复动作："+action+"；目标："+String.join(",",goalIds);
        String sourceKey="goal-recovery-"+AgentTaskFingerprint.of(requestId).substring(0,32);
        var source=sources.issue(user,sessionId,sourceBody,sourceKey);

        var receipt=new LinkedHashMap<String,Object>();receipt.put("schemaVersion","2.3");receipt.put("requestId",requestId);
        receipt.put("state","accepted");receipt.put("action",action);receipt.put("goalIds",goalIds);
        receipt.put("goalRevision",task.goalRevision());receipt.put("planRevision",task.planRevision());
        receipt.put("effectVersion",task.effectVersion());receipt.put("allowedActions",new ArrayList<>(available));
        contracts.validateGoalPayload("goalRecoveryResult",receipt);
        String sourceFingerprint=AgentTaskFingerprint.of(source);
        recoveries.save(new AgentGoalRecoveryEntity(requestKey,requestId,scope,taskId,fingerprint,sourceFingerprint,
            action,expectedGoal,expectedPlan,expectedEffect,receipt));
        return receipt;
    }

    /** 只读同一scope、task和requestId的公开原回执；内部版本修订行不能从公开接口读取。 */
    @Transactional(readOnly=true)
    public Map<String,Object> read(Long user,String sessionId,String taskId,String requestId) {
        if(requestId==null||requestId.isBlank()||requestId.length()>100)throw AgentTaskRegistryService.bad("恢复请求身份无效");
        var scope=registry.scope(user,sessionId);String key=AgentTaskFingerprint.of(List.of(scope.key(),requestId));
        var row=recoveries.findByRequestKey(key).orElseThrow(AgentTaskRegistryService::missing);
        if(!row.taskId().equals(taskId)||"revision".equals(row.action()))throw AgentTaskRegistryService.missing();
        return row.receipt();
    }

    /** 先执行精确字段和受控枚举检查，再用权威2.3 Schema验证数组长度、唯一性与数值范围。 */
    private void validateRequest(Map<String,Object> request) {
        if(request==null||!request.keySet().equals(REQUEST_FIELDS)||!"2.3".equals(request.get("schemaVersion"))
                ||!(request.get("requestId") instanceof String id)||id.isBlank()||id.length()>100
                ||!(request.get("action") instanceof String action)||!ACTIONS.contains(action))
            throw AgentTaskRegistryService.bad("目标恢复请求结构无效");
        try {contracts.validateGoalPayload("goalRecoveryRequest",request);}
        catch(AgentServiceUnavailableException invalid){throw AgentTaskRegistryService.bad("目标恢复请求结构无效");}
    }

    /** 从Schema已验证数组读取唯一非空字符串，并返回保持客户端顺序的副本。 */
    private static List<String> strings(Object value) {
        if(!(value instanceof List<?> rows))throw AgentTaskRegistryService.bad("目标恢复列表无效");
        var result=new ArrayList<String>();for(Object item:rows) {
            if(!(item instanceof String text)||text.isBlank()||text.length()>100)
                throw AgentTaskRegistryService.bad("目标恢复列表无效");
            result.add(text);
        }
        if(result.size()!=new LinkedHashSet<>(result).size())throw AgentTaskRegistryService.bad("目标恢复列表重复");
        return result;
    }

    /** 版本只接受协议整数，拒绝布尔、负数和浮点数宽松转换。 */
    private static long version(Map<String,Object> value,String field,long minimum) {
        Object raw=value.get(field);if(!(raw instanceof Integer)&&!(raw instanceof Long))
            throw AgentTaskRegistryService.bad("目标恢复版本无效");
        long number=((Number)raw).longValue();if(number<minimum)throw AgentTaskRegistryService.bad("目标恢复版本无效");
        return number;
    }
}
