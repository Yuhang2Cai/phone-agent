// 持久控制交接模块：预检/预备/回执/提交/安全退出均为短事务，HTTP和模型绝不在持锁事务中运行。
package com.fandazi.feature.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spring管理焦点与执行栅栏；Agent只提供持久安全停点回执，不能直接授予业务写权限。 */
@Service
public class AgentTaskControlService {
    private final AgentTaskRegistryService registry;private final AgentTaskRepository tasks;
    private final AgentTaskRunRepository runs;private final AgentTaskTurnRepository turns;
    private final AgentTaskCommandRepository commands;private final AgentConversationSourceService sources;
    private final AgentTaskEventOutbox outbox;private final AgentProtocolValidator contracts;
    private final ConfirmationGrantService grants;
    /** 注入同库事务组件，不注入HTTP客户端，防止控制预备期间持有跨服务长锁。 */
    public AgentTaskControlService(AgentTaskRegistryService registry,AgentTaskRepository tasks,AgentTaskRunRepository runs,
            AgentTaskTurnRepository turns,AgentTaskCommandRepository commands,AgentConversationSourceService sources,
            AgentTaskEventOutbox outbox,ObjectMapper mapper,ConfirmationGrantService grants) {
        this.registry=registry;this.tasks=tasks;this.runs=runs;this.turns=turns;this.commands=commands;this.sources=sources;
        this.outbox=outbox;contracts=new AgentProtocolValidator(mapper);
        this.grants=grants;
    }
    /** 按冻结Schema拒绝身份/价格/工具字段；非法公开输入统一400，不暴露Schema值。 */
    private void validate(String entry,Map<String,Object> value) {
        try {contracts.validateTaskPayload(entry,value);}catch(AgentServiceUnavailableException error){throw AgentTaskRegistryService.bad("任务请求结构不合法");}
    }
    /** 新目标描述来自已做来源区间与十三类分类的内部网关；不作为公开按钮自由字段。 */
    public record NewGoal(String requestId,String originalSpan,String kind,String intent,String title,long expectedFocusVersion) {}
    /** 插问先验证新目标再预备；新task只在安全提交后创建，拒绝/退出不留下幽灵任务。 */
    @Transactional
    public Map<String,Object> prepareInsert(Long user,String sessionId,NewGoal input) {
        if(input==null||input.requestId()==null||input.requestId().isBlank()||input.requestId().length()>100||input.originalSpan()==null||input.originalSpan().isBlank())
            throw AgentTaskRegistryService.bad("新目标输入无效");
        registry.validateDescription(input.kind(),input.intent(),input.title());
        // 问候/闲聊/范围外输入只能短回复，不能暂停用户的在途任务。
        if("chat".equals(input.kind()))throw AgentTaskRegistryService.bad("非业务插问不能改变任务");
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);String hash=AgentTaskFingerprint.of(input);
        var previous=commands.findByUserIdAndSessionIdAndEpochAndRequestId(user,sessionId,scope.epoch(),input.requestId());
        if(previous.isPresent()){if(!hash.equals(previous.get().fingerprint()))throw AgentTaskRegistryService.conflict("同请求身份关联了不同目标");return previous.get().projection();}
        if(session.focusVersion()!=input.expectedFocusVersion())throw AgentTaskRegistryService.conflict("插问焦点已变化");
        var affected=new LinkedHashMap<String,AgentTaskEntity>();
        if(session.currentTaskId()!=null){var front=registry.lockTask(scope,session.currentTaskId());
            if(front.pendingCommand()!=null)throw AgentTaskRegistryService.conflict("前台仍在交接");registry.previewControl(user,sessionId,"insert",front.id());affected.put(front.id(),front);}
        var leases=lockRunsAndTurns(affected.values());
        var fresh=new AgentTaskEntity(scope,input.kind(),input.intent(),input.title());
        var source=sources.issue(user,sessionId,input.originalSpan(),"tc-"+AgentTaskFingerprint.of(input.requestId()));
        var details=new LinkedHashMap<String,Object>();var ids=new TreeSet<>(affected.keySet());ids.add(fresh.id());details.put("affectedTaskIds",new ArrayList<>(ids));
        details.put("beforeFocusId",session.currentTaskId());details.put("changesFocus",true);
        details.put("newTask",Map.of("id",fresh.id(),"kind",input.kind(),"intent",input.intent(),"title",input.title()));
        session.advanceTaskFocus();var command=new AgentTaskCommandEntity(scope,input.requestId(),hash,"insert",fresh.id(),(String)source.get("sourceMessageId"),session.focusVersion(),details);
        var targets=new ArrayList<Map<String,Object>>();for(var task:affected.values()){task.prepareControl(command.id());targets.add(wireTarget(task));}
        targets.add(wireTarget(fresh));command.details().put("targets",targets);
        for(var turn:leases)turn.fenceOff();session.clearTaskConfirmation(affected.keySet());commands.saveAndFlush(command);
        grants.revokeTasks(user,sessionId,affected.keySet());
        for(var task:affected.values())outbox.append(session,"task_projection",task.projection());outbox.append(session,"command_status",command.projection());return command.projection();
    }
    /** 普通按钮预备；同请求只推进一次，容量/版本/目标冲突在任何栅栏变化前拒绝。 */
    @Transactional
    public Map<String,Object> prepare(Long user,String sessionId,Map<String,Object> raw) {
        validate("taskCommandRequest",raw);
        var canonical=new LinkedHashMap<>(raw);
        canonical.put("expectedTaskVersion",AgentTaskFingerprint.version(raw.get("expectedTaskVersion")));
        canonical.put("expectedFocusVersion",AgentTaskFingerprint.version(raw.get("expectedFocusVersion")));
        String fingerprint=AgentTaskFingerprint.of(canonical),request=(String)raw.get("requestId"),action=(String)raw.get("action"),targetId=(String)raw.get("targetTaskId");
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);
        var previous=commands.findByUserIdAndSessionIdAndEpochAndRequestId(user,sessionId,scope.epoch(),request);
        if(previous.isPresent()) {
            if(!previous.get().fingerprint().equals(fingerprint))throw AgentTaskRegistryService.conflict("同请求身份关联了不同控制内容");
            return previous.get().projection();
        }
        // 目标只从本人scope读取；恢复/切换同时影响原前台，取消后台则只影响后台自身。
        var target=tasks.findById(targetId).orElseThrow(AgentTaskRegistryService::missing);
        if(!target.ownedBy(scope))throw AgentTaskRegistryService.missing();
        var ids=new TreeSet<String>();ids.add(targetId);
        if(Set.of("resume","switch").contains(action)&&session.currentTaskId()!=null)ids.add(session.currentTaskId());
        var affected=new LinkedHashMap<String,AgentTaskEntity>();for(String id:ids)affected.put(id,registry.lockTask(scope,id));
        target=affected.get(targetId);
        if(target.version()!=AgentTaskFingerprint.version(canonical.get("expectedTaskVersion"))||session.focusVersion()!=AgentTaskFingerprint.version(canonical.get("expectedFocusVersion")))
            throw AgentTaskRegistryService.conflict("任务或焦点版本已变化");
        for(var task:affected.values())if(task.pendingCommand()!=null)throw AgentTaskRegistryService.conflict("目标仍有未完成控制交接");
        if(Set.of("pause","cancel").contains(action)&&"active".equals(target.lifecycle())&&!targetId.equals(session.currentTaskId()))throw AgentTaskRegistryService.conflict("目标不是当前焦点");
        registry.previewControl(user,sessionId,action,targetId);
        // 锁序固定session→按ID task→按ID run→turn→command；普通受理竞争相同租约。
        var leases=lockRunsAndTurns(affected.values());
        String body="任务按钮:"+action+":"+targetId+":"+canonical.get("expectedTaskVersion")+":"+canonical.get("expectedFocusVersion");
        var source=sources.issue(user,sessionId,body,"tc-"+AgentTaskFingerprint.of(request));
        var before=new LinkedHashMap<String,Object>();for(var task:affected.values())before.put(task.id(),task.projection());
        var details=new LinkedHashMap<String,Object>();details.put("affectedTaskIds",new ArrayList<>(ids));details.put("before",before);
        details.put("beforeFocusId",session.currentTaskId());
        boolean changesFocus=session.currentTaskId()!=null&&ids.contains(session.currentTaskId())||Set.of("resume","switch").contains(action);
        details.put("changesFocus",changesFocus);
        if(changesFocus)session.advanceTaskFocus();
        var command=new AgentTaskCommandEntity(scope,request,fingerprint,action,targetId,(String)source.get("sourceMessageId"),session.focusVersion(),details);
        // prepared保留原停点/生命周期，只推进受影响代次；已入场的旧turn立即不可继续执行。
        for(var task:affected.values())task.prepareControl(command.id());for(var turn:leases)turn.fenceOff();
        session.clearTaskConfirmation(ids);
        var wireTargets=new ArrayList<Map<String,Object>>();
        // 传输顺序有明确含义：被暂停原前台在前，待激活目标在后；数据库锁仍按ID排序。
        if(Set.of("resume","switch").contains(action)&&details.get("beforeFocusId")!=null&&!targetId.equals(details.get("beforeFocusId")))
            wireTargets.add(wireTarget(affected.get((String)details.get("beforeFocusId"))));
        wireTargets.add(wireTarget(target));command.details().put("targets",wireTargets);
        commands.saveAndFlush(command);
        // command持久后再按稳定顺序锁grant；撤销与新代次同库原子提交，退出不恢复旧凭证。
        grants.revokeTasks(user,sessionId,ids);
        for(var task:affected.values())outbox.append(session,"task_projection",task.projection());
        outbox.append(session,"command_status",command.projection());return command.projection();
    }
    /** 构造可信栅栏目标，终态推荐任务用lastRun检查点引用，但不复活该run。 */
    private Map<String,Object> wireTarget(AgentTaskEntity task) {
        var value=new LinkedHashMap<String,Object>();value.put("conversationTaskId",task.id());value.put("runId",task.activeRunId());
        value.put("expectedTaskVersion",task.version());value.put("expectedGeneration",task.generation());
        value.put("checkpointRef",task.activeRunId()!=null?task.activeRunId():task.lastRunId());return value;
    }
    /** 在task锁之后按稳定顺序锁run/turn；没有run的awaiting_followup也允许安全控制。 */
    private List<AgentTaskTurnEntity> lockRunsAndTurns(Collection<AgentTaskEntity> affected) {
        var runIds=new TreeSet<String>();var ids=new ArrayList<String>();
        for(var task:affected){ids.add(task.id());if(task.activeRunId()!=null)runIds.add(task.activeRunId());}
        for(String id:runIds)runs.lockById(id).orElseThrow(AgentTaskRegistryService::missing);
        return turns.lockLeases(ids);
    }
    /** 统一在session锁后读取不可变命令归属/目标，再按固定锁序取得所有持久行。 */
    private Locked lock(Long user,String sessionId,String id) {
        var session=registry.lockScope(user,sessionId);var scope=registry.scopeOf(session);
        var command=commands.findById(id).orElseThrow(AgentTaskRegistryService::missing);
        if(!command.scope().equals(scope))throw AgentTaskRegistryService.missing();
        var affected=new LinkedHashMap<String,AgentTaskEntity>();
        // 预分配新目标在commit前没有task行；只能跳过持久命令自己签发的reserved ID。
        for(String taskId:affectedIds(command)) {
            if(command.details().containsKey("newTask")&&taskId.equals(command.target())&&!"committed".equals(command.state()))continue;
            affected.put(taskId,registry.lockTask(scope,taskId));
        }
        var leases=lockRunsAndTurns(affected.values());command=commands.lockById(id).orElseThrow(AgentTaskRegistryService::missing);
        return new Locked(session,scope,command,affected,leases);
    }
    /** 内部聚合只用于持锁短事务，不传到网络层或公开目录。 */
    private record Locked(AgentSession session,AgentTaskRegistryService.Scope scope,AgentTaskCommandEntity command,
            Map<String,AgentTaskEntity> tasks,List<AgentTaskTurnEntity> leases) {}
    /** 目标ID由首次预备持久化；所有后续派发/对账使用原清单而非重新猜当前焦点。 */
    @SuppressWarnings("unchecked")
    private List<String> affectedIds(AgentTaskCommandEntity command) {return ((List<Object>)command.details().get("affectedTaskIds")).stream().map(String::valueOf).sorted().toList();}
    /** 读取本人命令公开状态；不返回检查点、租约或私有ACK正文。 */
    @Transactional(readOnly=true)
    public Map<String,Object> read(Long user,String sessionId,String id) {
        var scope=registry.scope(user,sessionId);var command=commands.findById(id).orElseThrow(AgentTaskRegistryService::missing);
        if(!command.scope().equals(scope))throw AgentTaskRegistryService.missing();
        return command.projection();
    }
    /** 提供后台可信scope定位，不接受模型/用户直接传入其他用户身份。 */
    @Transactional(readOnly=true)
    public AgentTaskRegistryService.Scope scope(String id) {return commands.findById(id).orElseThrow(AgentTaskRegistryService::missing).scope();}
    /** 在短事务认领派发；返回后才可HTTP。达到上限/待核查仅允许只读lookup。 */
    @Transactional
    public Map<String,Object> claim(Long user,String sessionId,String id,String owner,Instant now) {
        var locked=lock(user,sessionId,id);var command=locked.command();
        if(command.terminal()||"agent_acknowledged".equals(command.state())||command.leased(now)||!command.due(now))return null;
        if("needs_attention".equals(command.reconcileStatus()))return null;
        if(command.exhausted(now)) {
            command.attention("reconcile_limit");outbox.append(locked.session(),"command_status",command.projection());return null;
        }
        command.claim(owner,now);return wire(command);
    }
    /** 同命令只读核查有租约与退避；显式重查可越过时间退避，仍不能越过他人占用租约。 */
    @Transactional
    public boolean claimLookup(Long user,String sessionId,String id,String owner,Instant now,boolean explicit) {
        var locked=lock(user,sessionId,id);var command=locked.command();
        if(command.terminal()||command.leased(now)||(!explicit&&!command.due(now)))return false;
        command.claimLookup(owner,now);return true;
    }
    /** 网络查询在事务外；只有本次查询持有者能释放租约，不能覆盖迟到安全退出/提交。 */
    @Transactional
    public void finishLookup(Long user,String sessionId,String id,String owner,Instant now) {
        var command=lock(user,sessionId,id).command();if(!command.terminal()&&command.leaseOwned(owner))command.finishLookup(now);
    }
    /** 内部控制载荷是固定元数据；不包含新业务文本、价格、工具指令或grant。 */
    private Map<String,Object> wire(AgentTaskCommandEntity command) {
        var value=Map.<String,Object>of("schemaVersion","2.2","commandId",command.id(),"scope",AgentTaskFingerprint.scope(command.scope()),
            "action",command.action(),"decision","aborting".equals(command.state())?"abort":"apply","focusVersion",command.focusVersion(),
            "targets",command.details().get("targets"),"sourceMessageId",command.source());
        contracts.validateTaskPayload("taskControlRequest",value);return value;
    }
    /** HTTP失败保存稳定原因/退避；不修改任务停点或猜测写入结果。 */
    @Transactional
    public void dispatchFailed(Long user,String sessionId,String id,String owner,Instant now) {
        var locked=lock(user,sessionId,id);var command=locked.command();
        if(!command.terminal()&&command.leaseOwned(owner)){command.retry("agent_unavailable",now);outbox.append(locked.session(),"command_status",command.projection());}
    }
    /** 验证持久ACK的scope/目标/运行身份与结果；非法或不明证据不能提交焦点。 */
    private boolean safeAck(AgentTaskCommandEntity command,Map<String,Object> ack,String expectedStatus) {
        validate("controlAck",ack);
        if(!command.id().equals(ack.get("commandId"))||!AgentTaskFingerprint.scope(command.scope()).equals(ack.get("scope")))throw AgentTaskRegistryService.conflict("控制回执归属不一致");
        if(!expectedStatus.equals(ack.get("status"))||!Set.of("none","settled").contains(ack.get("writeOutcome")))return false;
        @SuppressWarnings("unchecked") var receipts=(List<Map<String,Object>>)ack.get("checkpoints");
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)command.details().get("targets");
        if(receipts.size()!=targets.size())return false;
        var seen=new HashSet<String>();
        for(var receipt:receipts) {
            String id=(String)receipt.get("conversationTaskId");if(!seen.add(id))return false;
            var target=targets.stream().filter(t -> id.equals(t.get("conversationTaskId"))).findFirst();
            if(target.isEmpty()||!Objects.equals(target.get().get("runId"),receipt.get("runId")))return false;
        }
        return true;
    }
    /** 第一短事务持久安全回执；HTTP重试/重复ACK不能多次推进任务版本。 */
    @Transactional
    public Map<String,Object> acknowledge(Long user,String sessionId,String id,Map<String,Object> ack) {
        var locked=lock(user,sessionId,id);var command=locked.command();
        if(command.terminal())return command.projection();
        // 退出决定先于迟到正常ACK；只有Agent退出墓碑与无不明写证据才能安全解除栅栏。
        if("aborting".equals(command.state())) {
            if(safeAck(command,ack,"aborted")) {
                requirePreparedFence(locked);
                for(var task:locked.tasks().values())task.abortControl();for(var turn:locked.leases())turn.release();
                command.finish("aborted");for(var task:locked.tasks().values())outbox.append(locked.session(),"task_projection",task.projection());
                outbox.append(locked.session(),"command_status",command.projection());
            } else {
                // 迟到正常ACK不能吞掉已持久退出决定；仍有派发预算时继续发送同命令abort。
                if("uncertain".equals(ack.get("writeOutcome")))command.attention("abort_evidence_pending");
                else command.retry("abort_evidence_pending",Instant.now());
                outbox.append(locked.session(),"command_status",command.projection());
            }
            return command.projection();
        }
        if("agent_acknowledged".equals(command.state())) {
            if(!AgentTaskFingerprint.of(command.ack()).equals(AgentTaskFingerprint.of(ack)))throw AgentTaskRegistryService.conflict("同命令回执内容冲突");
            return command.projection();
        }
        if(!safeAck(command,ack,"checkpoint_saved")) {
            if("uncertain".equals(ack.get("writeOutcome"))||Set.of("uncertain","rejected").contains(ack.get("status")))command.attention("write_or_checkpoint_uncertain");
            else command.retry("agent_processing",Instant.now());
            outbox.append(locked.session(),"command_status",command.projection());return command.projection();
        }
        command.acknowledge(ack);outbox.append(locked.session(),"command_status",command.projection());return command.projection();
    }
    /** 第二短事务提交生命周期/焦点/outbox；崩溃后直接使用持久ACK，不重派业务。 */
    @Transactional
    public Map<String,Object> commit(Long user,String sessionId,String id) {
        var locked=lock(user,sessionId,id);var command=locked.command();if(command.terminal())return command.projection();
        if(!"agent_acknowledged".equals(command.state())||!safeAck(command,command.ack(),"checkpoint_saved"))throw AgentTaskRegistryService.conflict("尚无安全控制回执");
        requirePreparedFence(locked);
        String original=(String)command.details().get("beforeFocusId"),target=command.target();
        // 先释放旧前台唯一占位并flush，再激活目标；避免JPA更新顺序引发短暂双前台唯一键冲突。
        if("insert".equals(command.action())) {
            if(original!=null){locked.tasks().get(original).commitLifecycle("paused",locked.scope());tasks.flush();}
            @SuppressWarnings("unchecked") var description=(Map<String,Object>)command.details().get("newTask");
            var fresh=AgentTaskEntity.reserved(locked.scope(),(String)description.get("kind"),(String)description.get("intent"),(String)description.get("title"),target);
            tasks.saveAndFlush(fresh);locked.tasks().put(target,fresh);locked.session().commitTaskFocus(target);locked.session().bindTaskRun(null,null);
        } else if(Set.of("resume","switch").contains(command.action())) {
            if(original!=null&&!original.equals(target)){locked.tasks().get(original).commitLifecycle("paused",locked.scope());tasks.flush();}
            var selected=locked.tasks().get(target);selected.commitLifecycle("active",locked.scope());locked.session().commitTaskFocus(target);
            locked.session().bindTaskRun(selected.activeRunId(),selected.activeRunId()==null?null:runs.findById(selected.activeRunId()).orElseThrow().status());
        } else {
            var selected=locked.tasks().get(target);String runId=selected.activeRunId();
            selected.commitLifecycle("cancel".equals(command.action())?"cancelled":"paused",locked.scope());
            if("cancel".equals(command.action())&&runId!=null) {var run=runs.findById(runId).orElseThrow();if(!run.terminal())run.stop("cancelled");}
            if(target.equals(original)){locked.session().commitTaskFocus(null);locked.session().bindTaskRun(null,null);}
        }
        for(var turn:locked.leases())turn.release();command.finish("committed");
        for(var task:locked.tasks().values())outbox.append(locked.session(),"task_projection",task.projection());
        outbox.append(locked.session(),"command_status",command.projection());
        outbox.append(locked.session(),"control_reply",Map.of("message","任务控制已完成","sourceMessageId",command.source()));return command.projection();
    }
    /** ACK只证明Agent停点；提交/安全退出均再次核验Spring预备版本与代次，后台取消不核验无关焦点。 */
    private void requirePreparedFence(Locked locked) {
        var command=locked.command();
        if(Boolean.TRUE.equals(command.details().get("changesFocus"))&&locked.session().focusVersion()!=command.focusVersion())
            throw AgentTaskRegistryService.conflict("控制焦点版本已变化");
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)command.details().get("targets");
        for(var task:locked.tasks().values()) {
            var expected=targets.stream().filter(t -> task.id().equals(t.get("conversationTaskId"))).findFirst().orElseThrow();
            if(!command.id().equals(task.pendingCommand())||task.version()!=AgentTaskFingerprint.version(expected.get("expectedTaskVersion"))
                    ||task.generation()!=AgentTaskFingerprint.version(expected.get("expectedGeneration")))throw AgentTaskRegistryService.conflict("任务控制栅栏已变化");
        }
    }
    /** 永久失败先登记不可逆退出决定；不因超时直接aborted，不恢复旧授权。 */
    @Transactional
    public Map<String,Object> beginAbort(Long user,String sessionId,String id) {
        var locked=lock(user,sessionId,id);var command=locked.command();
        if("aborted".equals(command.state())||"aborting".equals(command.state()))return command.projection();
        if(command.terminal())throw AgentTaskRegistryService.conflict("已提交控制不能回退");
        command.beginAbort();outbox.append(locked.session(),"command_status",command.projection());return command.projection();
    }
}
