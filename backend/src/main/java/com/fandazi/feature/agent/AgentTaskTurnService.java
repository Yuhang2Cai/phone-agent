// 普通轮次服务：与生命周期控制分开受理；持久单执行租约/完成补交，依赖同库session与task锁。
package com.fandazi.feature.agent;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 接收网关已解释的输入；无工具短回复不能伪造业务TaskFence。 */
@Service
public class AgentTaskTurnService {
    private final AgentTaskRegistryService registry;private final AgentTaskTurnRepository turns;private final AgentTaskRunRepository runs;
    private final AgentConversationSourceService sources;private final AgentTaskEventOutbox outbox;private final AgentProtocolValidator contracts;
    private final AgentConversationSourceRepository sourceRows;
    private final AgentCardEnrichmentService cards;private final AgentTaskExecutionContext execution;
    /** 注入同库持久组件；不依赖HTTP/模型/业务工具，保证受理和完成为短事务。 */
    public AgentTaskTurnService(AgentTaskRegistryService registry,AgentTaskTurnRepository turns,AgentTaskRunRepository runs,
            AgentConversationSourceService sources,AgentTaskEventOutbox outbox,ObjectMapper mapper,AgentConversationSourceRepository sourceRows,
            AgentCardEnrichmentService cards,AgentTaskExecutionContext execution) {
        this.registry=registry;this.turns=turns;this.runs=runs;this.sources=sources;this.outbox=outbox;this.sourceRows=sourceRows;
        this.cards=cards;this.execution=execution;contracts=new AgentProtocolValidator(mapper);
    }
    /** 内部输入对象由鉴权网关构造；task与版本仍须服务重新核验，不能相信模型身份。 */
    public record Input(String requestId,String body,String taskId,long taskVersion,long focusVersion,boolean executable) {}
    /** 返回持久轮次引用与可选业务许可；无工具路径只允许完成公开短回复。 */
    public record Accepted(String turnId,Map<String,Object> admission,Map<String,Object> completedResult) {}
    /** 网关原始输入与轮次在同一短事务绑定；避免受理后崩溃留下无法核验重试正文的半条许可。 */
    @Transactional
    public Accepted admitGatewayTurn(Long user,String sessionId,Input input,String originalHash) {
        return admitGatewayTurn(user,sessionId,input,originalHash,Map.of());
    }
    /** 显式公开对象证据在受理短事务内固化；网络执行只读该副本，不能从请求重新注入。 */
    @Transactional
    public Accepted admitGatewayTurn(Long user,String sessionId,Input input,String originalHash,Map<String,Object> selectedContext) {
        if(originalHash==null||!originalHash.matches("[a-f0-9]{64}"))throw AgentTaskRegistryService.bad("网关输入摘要无效");
        var accepted=admitTurn(user,sessionId,input);var session=registry.lockScope(user,sessionId);
        var turn=lockTurn(session,accepted.turnId());turn.bindGatewayInput(originalHash,input.body());turn.bindGatewayContext(selectedContext);return accepted;
    }
    /** 规范化输入→同scope查重→CAS/租约核验→签发不可变许可；租约过期不能重跑工作流。 */
    @Transactional
    public Accepted admitTurn(Long user,String sessionId,Input input) {
        if(input==null||input.requestId()==null||input.requestId().isBlank()||input.requestId().length()>100||input.body()==null||input.body().isBlank())
            throw AgentTaskRegistryService.bad("轮次输入无效");
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);
        String hash=AgentTaskFingerprint.of(input);var old=turns.findByUserIdAndSessionIdAndEpochAndRequestId(user,sessionId,scope.epoch(),input.requestId());
        // 查重必须早于当前版本检查：首轮完成/控制后同输入仍只回读，绝不能取得新许可。
        if(old.isPresent()){if(!hash.equals(old.get().fingerprint()))throw AgentTaskRegistryService.conflict("同轮次请求正文已变化");return accepted(old.get());}
        AgentTaskEntity task=null;AgentTaskRunEntity run=null;
        if(input.executable()) {
            if(input.taskId()==null)throw AgentTaskRegistryService.bad("可执行轮次缺少目标");task=registry.lockTask(scope,input.taskId());
            if(task.pendingCommand()!=null||!"active".equals(task.lifecycle())||!task.id().equals(session.currentTaskId())
                    ||task.version()!=input.taskVersion()||session.focusVersion()!=input.focusVersion())throw AgentTaskRegistryService.conflict("轮次目标或版本已变化");
            if(task.activeRunId()!=null)runs.lockById(task.activeRunId()).orElseThrow(AgentTaskRegistryService::missing);
            // 即使原租约到期，也只能对账/安全停点；不得创建第二个执行者。
            if(turns.findByLeaseTaskKey(task.id()).isPresent())throw AgentTaskRegistryService.conflict("任务已有待核查执行轮次");
            run=registry.beginRun(user,sessionId,task.id());
        } else if(input.taskId()!=null) {
            // 无工具回复引用任务时仍校验归属，但不变更该task/trace/确认/版本。
            registry.lockTask(scope,input.taskId());
        }
        var source=sources.issue(user,sessionId,input.body(),"tt-"+AgentTaskFingerprint.of(input.requestId()));
        var turn=new AgentTaskTurnEntity(scope,input.requestId(),hash,(String)source.get("sourceMessageId"),
            input.taskId(),run==null?null:run.id(),UUID.randomUUID().toString(),input.executable());
        if(task!=null) {
            var fence=Map.<String,Object>of("scope",AgentTaskFingerprint.scope(scope),"conversationTaskId",task.id(),"runId",run.id(),"turnId",turn.id(),
                "focusVersion",session.focusVersion(),"taskVersion",task.version(),"executionGeneration",task.generation());
            contracts.validateTaskPayload("taskFence",fence);turn.bindFence(fence);session.bindTaskTrace(turn.traceId());
        }
        turns.saveAndFlush(turn);return accepted(turn);
    }
    /** 不为无工具轮次构造虚假TaskFence；已完成受理只回读首次结果并禁止工具。 */
    private Accepted accepted(AgentTaskTurnEntity turn) {
        var admission=turn.fence()==null?null:turn.admission();if(admission!=null)contracts.validateTaskPayload("turnAdmission",admission);
        return new Accepted(turn.id(),admission,turn.result());
    }
    /** 完成锁序同控制：session→task→run→turn；任何来源/账号/epoch不一致均拒绝。 */
    private AgentTaskTurnEntity lockTurn(AgentSession session,String id) {
        var scope=registry.scopeOf(session);var row=turns.findById(id).orElseThrow(AgentTaskRegistryService::missing);
        if(!row.scope().equals(scope))throw AgentTaskRegistryService.missing();
        if(row.taskId()!=null)registry.lockTask(scope,row.taskId());if(row.runId()!=null)runs.lockById(row.runId()).orElseThrow(AgentTaskRegistryService::missing);
        return turns.lockById(id).orElseThrow(AgentTaskRegistryService::missing);
    }
    /** 内部权威回读只返回原输入摘要/来源/许可及完成回执，不受理新输入、不推进任何任务版本。 */
    @Transactional
    public Map<String,Object> readAuthority(Long user,String sessionId,String epoch,String turnId) {
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);
        if(!scope.epoch().equals(epoch))throw new ToolBoundaryException("FORBIDDEN","轮次范围已失效");
        var turn=lockTurn(session,turnId);var source=sourceRows.findById(turn.source()).orElseThrow(AgentTaskRegistryService::missing);
        if(!source.ownedBy(user,sessionId,epoch))throw new ToolBoundaryException("FORBIDDEN","轮次来源不属于当前范围");
        var admission=turn.fence()==null?null:new LinkedHashMap<>(turn.admission());
        if(admission!=null&&"admitted".equals(turn.state())) {
            var task=registry.lockTask(scope,turn.taskId());var run=runs.lockById(turn.runId()).orElseThrow(AgentTaskRegistryService::missing);var fence=turn.fence();
            // 许可行未显式fenceOff也不能绕过当前焦点/代次；完成回读仍保留原结果供补交核验。
            boolean current=turn.executable()&&task.pendingCommand()==null&&"active".equals(task.lifecycle())&&task.id().equals(session.currentTaskId())
                &&run.id().equals(task.activeRunId())&&!run.terminal()&&turn.traceId().equals(session.getTraceId())
                &&task.version()==AgentTaskFingerprint.version(fence.get("taskVersion"))&&task.generation()==AgentTaskFingerprint.version(fence.get("executionGeneration"))
                &&session.focusVersion()==AgentTaskFingerprint.version(fence.get("focusVersion"));
            if(!current){admission.put("admissionState","rejected");admission.put("mayExecuteTools",false);}
        }
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","2.2");result.put("traceId",turn.traceId());result.put("bodySha256",source.bodySha256());
        result.put("source",source.publicSource());result.put("admission",admission);result.put("completedResult",turn.result());
        // Agent用原受理ID回读持久归属，不能接收请求context自报的另一份分类/控制结论。
        result.put("requestId",turn.requestId());return result;
    }
    /** 无工具完成只生成control_reply；同turn同文回读、异文409，不动前台资格/trace/确认。 */
    @Transactional
    public Map<String,Object> completeReply(Long user,String sessionId,String turnId,String message) {
        if(message==null||message.isBlank()||message.length()>1000)throw AgentTaskRegistryService.bad("短回复无效");
        var session=registry.lockScope(user,sessionId);var turn=lockTurn(session,turnId);
        if(turn.fence()!=null)throw AgentTaskRegistryService.conflict("业务轮次必须提交完整结果");
        var result=Map.<String,Object>of("message",message,"sourceMessageId",turn.source());String hash=AgentTaskFingerprint.of(result);
        if("completed".equals(turn.state()))return repeated(turn,hash);
        turn.complete(result,hash);outbox.append(session,"control_reply",result);return result;
    }
    /** 幂等补交先核对原结果；完成后任务版本变化不应迫使业务工作流再跑。 */
    private Map<String,Object> repeated(AgentTaskTurnEntity turn,String hash) {
        if(!hash.equals(turn.resultFingerprint()))throw AgentTaskRegistryService.conflict("同轮次完成结果已变化");return turn.result();
    }
    /** 私有完整结果完成：校验Schema/来源/事件栅栏，再原子保存停点、确认摘要、投影与回执。 */
    @Transactional
    public Map<String,Object> complete(Long user,String sessionId,Map<String,Object> raw) {
        contracts.validateTaskPayload("turnCompletionRequest",raw);
        var session=registry.lockScope(user,sessionId);var turn=lockTurn(session,(String)raw.get("turnId"));
        String hash=AgentTaskFingerprint.of(raw);if("completed".equals(turn.state()))return repeated(turn,hash);
        if(!turn.executable()||!Objects.equals(turn.source(),raw.get("sourceMessageId"))||!AgentTaskFingerprint.of(turn.fence()).equals(AgentTaskFingerprint.of(raw.get("fence"))))
            throw AgentTaskRegistryService.conflict("轮次许可或来源已失效");
        var task=registry.lockTask(registry.scopeOf(session),turn.taskId());var fence=turn.fence();
        if(task.pendingCommand()!=null||!"active".equals(task.lifecycle())||!task.id().equals(session.currentTaskId())||!turn.runId().equals(task.activeRunId())
                ||session.focusVersion()!=AgentTaskFingerprint.version(fence.get("focusVersion"))||task.version()!=AgentTaskFingerprint.version(fence.get("taskVersion"))
                ||task.generation()!=AgentTaskFingerprint.version(fence.get("executionGeneration")))throw AgentTaskRegistryService.conflict("旧轮次结果不能覆盖新任务");
        @SuppressWarnings("unchecked") var result=(Map<String,Object>)raw.get("result");
        if(!turn.runId().equals(result.get("runId"))||!turn.traceId().equals(result.get("traceId")))throw AgentTaskRegistryService.conflict("完成运行身份不一致");
        @SuppressWarnings("unchecked") var events=(List<Map<String,Object>>)result.get("events");String field=null,goal=null;
        int inputStops=0,confirmationStops=0;String status=(String)result.get("status");
        for(var event:events) {
            @SuppressWarnings("unchecked") var context=(Map<String,Object>)event.get("taskContext");
            var expected=new LinkedHashMap<>(fence);expected.remove("scope");expected.remove("runId");
            if(!AgentTaskFingerprint.of(context).equals(AgentTaskFingerprint.of(expected))||!turn.traceId().equals(event.get("traceId"))||!turn.runId().equals(event.get("runId")))
                throw AgentTaskRegistryService.conflict("公开事件不属于当前轮次");
            @SuppressWarnings("unchecked") var payload=(Map<String,Object>)event.get("payload");
            if("input_required".equals(event.get("eventType"))) {
                if(!"waiting_input".equals(status)||!status.equals(event.get("status")))throw AgentTaskRegistryService.conflict("完成结果与输入停点矛盾");
                inputStops++;field=(String)payload.get("inputKey");
            }
            if("confirmation_required".equals(event.get("eventType"))) {
                if(!"waiting_confirmation".equals(status)||!status.equals(event.get("status")))throw AgentTaskRegistryService.conflict("完成结果与确认停点矛盾");
                confirmationStops++;
                @SuppressWarnings("unchecked") var summary=(Map<String,Object>)payload.get("summary");
                // Schema合法不代表已获领域授权：在已锁定且核验通过的任务栅栏内重查真实资源/金额/规则和有效grant。
                // 只保存服务端规范摘要，确保随后按钮提交的已展示内容与领域校验结果一致；缺grant时整个完成事务回滚。
                var canonical=execution.within(fence,()->cards.enrichSummary(user,sessionId,summary));
                session.awaitTaskConfirmation(task.id(),canonical);
            }
        }
        // 等待停点必须恰有一个对应事件；重复/缺失不能任取一个摘要或伪造可恢复状态。
        if(("waiting_input".equals(status)&&inputStops!=1)||("waiting_confirmation".equals(status)&&confirmationStops!=1))
            throw AgentTaskRegistryService.conflict("等待停点缺失或重复");
        // 停点与完成回执/outbox共用本事务；失败时一起回滚，不产生只改目录未保存结果的状态。
        var projection=registry.recordRunStop(user,sessionId,task.id(),turn.runId(),(String)result.get("status"),field,goal);
        turn.complete(raw,hash);outbox.append(session,"task_projection",projection);return raw;
    }
}
