// 权威任务栅栏模块：按session→task→run→turn锁序验证持久许可，不依赖Agent缓存或模型身份。
package com.fandazi.feature.agent;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 校验、确认与领域工具必须同一Spring事务；本模块不执行HTTP或模型。 */
@Service
public class AgentTaskFenceService {
    private final AgentTaskRegistryService registry;private final AgentTaskRunRepository runs;private final AgentTaskTurnRepository turns;
    private final AgentProtocolValidator contracts;
    private final ConfirmationGrantService grants;private final AgentToolExecutionRepository executions;
    /** 注入持久注册与轮次仓储，结构合法后仍逐项核对数据库权威状态。 */
    public AgentTaskFenceService(AgentTaskRegistryService registry,AgentTaskRunRepository runs,AgentTaskTurnRepository turns,ObjectMapper mapper,
            ConfirmationGrantService grants,AgentToolExecutionRepository executions){
        this.registry=registry;this.runs=runs;this.turns=turns;this.grants=grants;this.executions=executions;contracts=new AgentProtocolValidator(mapper);
    }
    /** 只在事务内使用的已锁聚合，不能序列化到模型、公开目录或日志。 */
    record Current(AgentSession session,AgentTaskEntity task,AgentTaskRunEntity run,AgentTaskTurnEntity turn) {}
    /** 校验输入→规范scope→固定锁序→许可/代次/焦点核验，任一失效在领域写前拒绝。 */
    @Transactional(propagation=Propagation.MANDATORY)
    Current requireCurrent(Map<String,Object> fence,String trace) {
        try{contracts.validateTaskPayload("taskFence",fence);}catch(AgentServiceUnavailableException invalid){throw new ToolBoundaryException("VALIDATION_ERROR","工具任务栅栏结构无效");}
        try {
            @SuppressWarnings("unchecked") var scope=(Map<String,Object>)fence.get("scope");Long user=Long.valueOf((String)scope.get("userId"));String sessionId=(String)scope.get("sessionId");
            var session=registry.lockScope(user,sessionId);var actual=registry.scopeOf(session);
            if(!actual.epoch().equals(scope.get("conversationEpoch")))throw new ToolBoundaryException("FORBIDDEN","任务范围已失效");
            var task=registry.lockTask(actual,(String)fence.get("conversationTaskId"));
            var run=runs.lockById((String)fence.get("runId")).orElseThrow(()->new ToolBoundaryException("FORBIDDEN","运行归属不可用"));
            var turn=turns.lockById((String)fence.get("turnId")).orElseThrow(()->new ToolBoundaryException("FORBIDDEN","轮次许可不可用"));
            if(!turn.scope().equals(actual)||!task.id().equals(run.taskId())||!task.id().equals(turn.taskId())||!run.id().equals(turn.runId()))
                throw new ToolBoundaryException("FORBIDDEN","轮次或运行不属于任务");
            // 过期租约不授予第二次执行；当前原许可可回查/补交，控制会明确fenced而非依赖时间猜测。
            if(task.pendingCommand()!=null||!"active".equals(task.lifecycle())||!task.id().equals(session.currentTaskId())||!run.id().equals(task.activeRunId())||run.terminal()
                    ||task.version()!=AgentTaskFingerprint.version(fence.get("taskVersion"))||task.generation()!=AgentTaskFingerprint.version(fence.get("executionGeneration"))
                    ||session.focusVersion()!=AgentTaskFingerprint.version(fence.get("focusVersion"))||!turn.executable()||!"admitted".equals(turn.state())
                    ||!AgentTaskFingerprint.of(fence).equals(AgentTaskFingerprint.of(turn.fence()))||!turn.traceId().equals(trace)||!session.getTraceId().equals(trace))
                throw new ToolBoundaryException("CONFLICT","任务执行许可已变化，请查询当前停点");
            return new Current(session,task,run,turn);
        } catch(ResponseStatusException unavailable){throw new ToolBoundaryException(unavailable.getStatusCode().value()==404?"FORBIDDEN":"CONFLICT","任务归属或状态已变化");}
        catch(NumberFormatException invalid){throw new ToolBoundaryException("FORBIDDEN","任务账号不可用");}
    }
    /** 2.3工具在读取缓存和领域写前核当前三版本及目标集合；旧版本或待发布目标一律拒绝。 */
    void requireCurrentGoal(Current current,Map<String,Object> goalFence) {
        if(goalFence==null||!goalFence.keySet().equals(Set.of("goalRevision","planRevision","effectVersion","goalIds"))
                ||!(goalFence.get("goalIds") instanceof List<?> goalIds)||goalIds.isEmpty()
                ||goalIds.stream().anyMatch(value->!(value instanceof String)))
            throw new ToolBoundaryException("VALIDATION_ERROR","目标围栏结构无效");
        long goal=AgentTaskFingerprint.version(goalFence.get("goalRevision"));
        long plan=AgentTaskFingerprint.version(goalFence.get("planRevision"));
        long effect=AgentTaskFingerprint.version(goalFence.get("effectVersion"));
        if(!"2.3".equals(current.task().protocolVersion())||current.task().goalRevision()!=goal
                ||current.task().planRevision()!=plan||current.task().effectVersion()!=effect)
            throw new ToolBoundaryException("CONFLICT","目标版本已变化，请查询当前目标");
        Map<String,Object> projection=current.task().goalProjection();
        if(projection==null||!(projection.get("outcomes") instanceof List<?> outcomes))
            throw new ToolBoundaryException("CONFLICT","目标投影尚未提交");
        Set<String> currentIds=new HashSet<>();
        for(Object value:outcomes)if(value instanceof Map<?,?> item&&item.get("goalId") instanceof String id)currentIds.add(id);
        if(goalIds.size()!=new HashSet<>(goalIds).size()||!currentIds.containsAll(goalIds))
            throw new ToolBoundaryException("CONFLICT","目标围栏引用不存在的目标");
    }
    /** 普通输入显式确认绑定本turn及展示摘要；调用方必须先核验用户按钮/来源，不接受模型确认建议。 */
    @Transactional
    public void confirmCurrent(Map<String,Object> fence,String trace,Map<String,Object> shownSummary) {
        var current=requireCurrent(fence,trace);
        if(!current.task().id().equals(current.session().pendingConfirmationTaskId())||!AgentTaskFingerprint.of(current.session().getPendingConfirmation()).equals(AgentTaskFingerprint.of(shownSummary)))
            throw new ToolBoundaryException("CONFLICT","确认对象与当前展示摘要不一致");
        current.session().confirmTaskTurn(current.turn().id());
    }
    /** 参数修订管理入口：只接收已校验字段名和动作版本，不执行任何业务工具；撤销结果必须持久幂等。 */
    @Transactional
    public Map<String,Object> invalidatePreparation(Map<String,Object> raw) {
        // 管理请求采用固定白名单，不接受凭证ID、业务正文或模型指定的撤销目标；结构错误不触碰授权。
        var fields=Set.of("voucherId","dealId","orderId","quantity","reason","subject","description","couponId","storeId");
        if(raw==null||!raw.keySet().equals(Set.of("schemaVersion","fence","traceId","actionVersion","changedFields"))
                ||!"2.2".equals(raw.get("schemaVersion"))||!(raw.get("fence") instanceof Map<?,?>)
                ||!(raw.get("traceId") instanceof String trace)||trace.isBlank()
                ||!(raw.get("changedFields") instanceof List<?> changes)||changes.isEmpty()||changes.size()>fields.size()
                ||changes.stream().anyMatch(value->!(value instanceof String)||!fields.contains(value))||new HashSet<>(changes).size()!=changes.size())
            throw new ToolBoundaryException("VALIDATION_ERROR","参数修订请求无效");
        if(!(raw.get("actionVersion") instanceof Integer||raw.get("actionVersion") instanceof Long))
            throw new ToolBoundaryException("VALIDATION_ERROR","修订动作版本必须为整数");
        int version;
        try{version=Math.toIntExact(AgentTaskFingerprint.version(raw.get("actionVersion")));}
        catch(RuntimeException invalid){throw new ToolBoundaryException("VALIDATION_ERROR","修订动作版本无效");}
        if(version<2)throw new ToolBoundaryException("VALIDATION_ERROR","修订动作版本无效");
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)raw.get("fence");var current=requireCurrent(fence,trace);
        String node="internal-preparation-invalidation",key=current.run().id()+":"+node+":"+version;
        if(key.length()>255)throw new ToolBoundaryException("VALIDATION_ERROR","修订身份过长");
        // session/task/turn已锁后才读取原动作回执；同键不同字段或栅栏拒绝，同内容回读不能再次撤销新grant。
        String hash=AgentTaskFingerprint.of(raw);var old=executions.findById(key);
        if(old.isPresent()){
            if(!hash.equals(old.get().getFingerprint()))throw new ToolBoundaryException("CONFLICT","修订动作键已关联其他内容");
            return old.get().getResult();
        }
        grants.revokeTasks(current.turn().scope().userId(),current.turn().scope().sessionId(),Set.of(current.task().id()));
        current.session().clearTaskConfirmation(Set.of(current.task().id()));
        var receipt=Map.<String,Object>of("schemaVersion","2.2","state","invalidated","actionVersion",version);
        var request=new InternalV2ToolRequest("2.0",String.valueOf(current.turn().scope().userId()),current.turn().scope().sessionId(),trace,
            current.run().id(),node,key,version,Map.of("changedFields",changes),fence);
        // 复用私有执行回执表保持撤销与去重原子；此内部管理名称不注册进17个模型工具。
        executions.saveAndFlush(new AgentToolExecution(request,current.turn().scope().userId(),"internal_invalidate_preparation",hash,receipt));return receipt;
    }
}
