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
    /** 注入持久注册与轮次仓储，结构合法后仍逐项核对数据库权威状态。 */
    public AgentTaskFenceService(AgentTaskRegistryService registry,AgentTaskRunRepository runs,AgentTaskTurnRepository turns,ObjectMapper mapper){
        this.registry=registry;this.runs=runs;this.turns=turns;contracts=new AgentProtocolValidator(mapper);
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
    /** 普通输入显式确认绑定本turn及展示摘要；调用方必须先核验用户按钮/来源，不接受模型确认建议。 */
    @Transactional
    public void confirmCurrent(Map<String,Object> fence,String trace,Map<String,Object> shownSummary) {
        var current=requireCurrent(fence,trace);
        if(!current.task().id().equals(current.session().pendingConfirmationTaskId())||!AgentTaskFingerprint.of(current.session().getPendingConfirmation()).equals(AgentTaskFingerprint.of(shownSummary)))
            throw new ToolBoundaryException("CONFLICT","确认对象与当前展示摘要不一致");
        current.session().confirmTaskTurn(current.turn().id());
    }
}
