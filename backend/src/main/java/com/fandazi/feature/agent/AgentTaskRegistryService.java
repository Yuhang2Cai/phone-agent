// 任务注册模块：Spring持久任务归属、唯一前台/容量、task—run关系和只读目录，依赖会话行锁及权威Schema。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 不执行模型或领域工具；事务只包含本库调度读写，禁止持锁跨HTTP调用。 */
@Service
public class AgentTaskRegistryService {
    private final AgentSessionRepository sessions;
    private final AgentTaskRepository tasks;
    private final AgentTaskRunRepository runs;
    private final AgentProtocolValidator contracts;
    // 容量是本轮冻结规则，生产留存/容量调整另行确认，不从模型请求读取。
    static final int MAX_PAUSED=5;
    private static final Set<String> KINDS=Set.of("selection","lookup","transaction","composite","chat");
    // 同一一级意图可有业务子动作；查询订单与购买共用group_buy_order，但任务类型不同。
    private static final Map<String,Set<String>> INTENT_KINDS=Map.ofEntries(
        Map.entry("store_recommendation",Set.of("selection")),Map.entry("deal_recommendation",Set.of("selection")),
        Map.entry("store_info",Set.of("lookup")),Map.entry("review_query",Set.of("lookup")),
        Map.entry("group_buy_order",Set.of("lookup","transaction")),Map.entry("coupon",Set.of("lookup")),
        Map.entry("refund_after_sale",Set.of("transaction")),Map.entry("complaint",Set.of("transaction")),
        Map.entry("faq",Set.of("lookup")),Map.entry("out_of_scope",Set.of("chat")),
        Map.entry("greeting",Set.of("chat")),Map.entry("chitchat",Set.of("chat")),Map.entry("composite",Set.of("composite")));

    /** 可信归属由已鉴权用户与持久session构造；epoch不能被客户端覆写。 */
    public record Scope(Long userId,String sessionId,String epoch) {
        /** 稳定唯一键用长度前缀避免分隔符碰撞；实际字段上限由会话入口限制。 */
        String key() { return userId+":"+sessionId.length()+":"+sessionId+":"+epoch; }
    }

    /** 注入真实仓库并预编译受控协议；契约装配失败则不启动部分目录能力。 */
    public AgentTaskRegistryService(AgentSessionRepository sessions,AgentTaskRepository tasks,
            AgentTaskRunRepository runs,ObjectMapper mapper) {
        this.sessions=sessions;this.tasks=tasks;this.runs=runs;contracts=new AgentProtocolValidator(mapper);
    }

    /** 从持久会话核验归属；不存在和跨用户均404，旧协议隔离为409。 */
    Scope scopeOf(AgentSession session) {
        if(!Set.of("2.2","2.3").contains(session.getContextProtocolVersion())||session.conversationEpoch()==null)
            throw conflict("当前会话未启用任务协议");
        return new Scope(session.getUser().getId(),session.getSessionId(),session.conversationEpoch());
    }
    /** 写事务第一把锁始终是session，调用方不得先锁task或run再调用。 */
    AgentSession lockScope(Long user,String sessionId) {
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId,user).orElseThrow(AgentTaskRegistryService::missing);
        scopeOf(session);return session;
    }
    /** 会话锁后锁定所属task；旧epoch与其他scope统一404，不泄露任务存在性。 */
    AgentTaskEntity lockTask(Scope scope,String taskId) {
        var task=tasks.lockOwned(taskId,scope.userId(),scope.sessionId(),scope.epoch()).orElseThrow(AgentTaskRegistryService::missing);
        if(!task.ownedBy(scope))throw missing();return task;
    }
    /** 查询限定完整scope；稳定排序只影响展示，不能代替指代消除。 */
    List<AgentTaskEntity> scopedTasks(Scope scope) {
        return tasks.findLiveScope(scope.userId(),scope.sessionId(),scope.epoch());
    }
    /** 只读取得当前完整scope，旧epoch命令/轮次不能借相同会话名重新获得访问资格。 */
    @Transactional(readOnly=true)
    public Scope scope(Long user,String sessionId) {
        return scopeOf(sessions.findBySessionIdAndUserId(sessionId,user).orElseThrow(AgentTaskRegistryService::missing));
    }

    /** 新建唯一前台目标；重复前台拒绝，不能覆盖或自动暂停旧任务。 */
    @Transactional
    public Map<String,Object> create(Long user,String sessionId,String kind,String intent,String title) {
        // 输入先校验，再读取归属和行锁；公开投影用权威意图枚举再次约束。
        validateDescription(kind,intent,title);
        var session=lockScope(user,sessionId);var scope=scopeOf(session);var rows=scopedTasks(scope);
        // 第二个前台必须显式走持久控制交接，不能在普通创建里自动挂起旧任务。
        if(session.currentTaskId()!=null||rows.stream().anyMatch(t -> "active".equals(t.lifecycle())))throw conflict("已有前台任务");
        if(rows.stream().filter(t -> "paused".equals(t.lifecycle())).count()>MAX_PAUSED)throw conflict("暂停容量异常");
        var task=new AgentTaskEntity(scope,kind,intent,title);task.bindProtocol(session.getContextProtocolVersion());
        contracts.validateTaskPayload("publicTask",task.projection());
        tasks.saveAndFlush(task);session.bindTaskFocus(task.id());
        return task.projection();
    }
    /** 创建和插问共享冻结的十三意图/任务类型边界；模型不能自造业务类型。 */
    void validateDescription(String kind,String intent,String title) {
        if(kind==null||!KINDS.contains(kind)||intent==null||!INTENT_KINDS.getOrDefault(intent,Set.of()).contains(kind)
                ||title==null||title.isBlank()||title.length()>120)throw bad("任务描述无效");
    }

    /** 读取目录与水位使用同一个可重复读快照；不初始化epoch、不写历史、不调用任何工具。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> directory(Long user,String sessionId) {
        var session=sessions.findBySessionIdAndUserId(sessionId,user).orElseThrow(AgentTaskRegistryService::missing);
        var scope=scopeOf(session);var rows=scopedTasks(scope);
        var active=rows.stream().filter(t -> "active".equals(t.lifecycle())).toList();
        // 数据异常不能猜一个前台返回；唯一约束之外还核对session焦点与task状态。
        if(active.size()>1||(active.isEmpty()!=(session.currentTaskId()==null))||
                (!active.isEmpty()&&!active.get(0).id().equals(session.currentTaskId())))throw conflict("任务焦点需要对账");
        var paused=rows.stream().filter(t -> "paused".equals(t.lifecycle()))
                .sorted(Comparator.comparing(AgentTaskEntity::pausedAt,Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AgentTaskEntity::createdAt).thenComparing(AgentTaskEntity::id)).toList();
        if(paused.size()>MAX_PAUSED)throw conflict("任务容量需要对账");
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","2.2");result.put("focusVersion",session.focusVersion());
        result.put("throughTaskEventId",session.taskEventSequence());result.put("currentTask",active.isEmpty()?null:active.get(0).projection());
        result.put("pausedTasks",paused.stream().map(AgentTaskEntity::projection).toList());
        contracts.validateTaskPayload("taskDirectory",result);return result;
    }

    /** 读取已知task公开投影，终态历史可读但不能因此恢复业务执行资格。 */
    @Transactional(readOnly=true)
    public Map<String,Object> task(Long user,String sessionId,String taskId,String expectedEpoch) {
        var session=sessions.findBySessionIdAndUserId(sessionId,user).orElseThrow(AgentTaskRegistryService::missing);
        var scope=scopeOf(session);
        if(!scope.epoch().equals(expectedEpoch))throw missing();
        var task=tasks.findById(taskId).orElseThrow(AgentTaskRegistryService::missing);
        if(!task.ownedBy(scope))throw missing();return task.projection();
    }

    /** 分配或恢复同task唯一在途run；终态推荐跟进分配新run，终态task不能重开。 */
    @Transactional
    public AgentTaskRunEntity beginRun(Long user,String sessionId,String taskId) {
        var session=lockScope(user,sessionId);var task=lockTask(scopeOf(session),taskId);
        if(task.pendingCommand()!=null)throw conflict("任务控制尚未交接完成");
        // 先核验当前可执行焦点，再恢复原在途run；仅推荐跟进等无在途run状态才创建新ID。
        if(!"active".equals(task.lifecycle())||!task.id().equals(session.currentTaskId()))throw conflict("任务不在前台");
        if(task.activeRunId()!=null) {
            var run=runs.lockById(task.activeRunId()).orElseThrow(AgentTaskRegistryService::missing);
            if(run.terminal()||!run.taskId().equals(taskId))throw conflict("运行归属需要对账");return run;
        }
        var run=runs.saveAndFlush(new AgentTaskRunEntity(taskId));task.startRun(run.id());session.bindTaskRun(run.id(),run.status());return run;
    }

    /** 提交停点并保持终态不可变；TL-03轮次完成事务将复用此方法并原子发布outbox。 */
    @Transactional
    public Map<String,Object> recordRunStop(Long user,String sessionId,String taskId,String runId,String status,String field,String goal) {
        if(!Set.of("running","waiting_input","waiting_confirmation","succeeded","cancelled","failed").contains(status))throw bad("运行状态无效");
        if((field!=null&&field.length()>100)||(goal!=null&&goal.length()>100))throw bad("停点身份无效");
        var session=lockScope(user,sessionId);var task=lockTask(scopeOf(session),taskId);
        if(task.pendingCommand()!=null)throw conflict("任务控制尚未交接完成");
        var run=runs.lockById(runId).orElseThrow(AgentTaskRegistryService::missing);
        if(!run.taskId().equals(taskId))throw missing();
        // 重复终态补交只回读；不同终态或旧run不能改写任务，更不能清空新焦点。
        if(run.terminal()) {
            if(!run.status().equals(status))throw conflict("终态run不可修改");return task.projection();
        }
        if(!runId.equals(task.activeRunId())||!"active".equals(task.lifecycle())||!taskId.equals(session.currentTaskId()))throw conflict("任务已变化");
        run.stop(status);task.recordStop(status,field,goal);
        if(!"active".equals(task.lifecycle()))session.bindTaskFocus(null);
        session.bindTaskRun(task.activeRunId(),run.status());return task.projection();
    }

    /** 仅预检控制容量；不暂停/恢复、撤销确认或修改任何版本，实际交接由TL-03持久命令完成。 */
    @Transactional(readOnly=true)
    public int previewControl(Long user,String sessionId,String action,String targetTaskId) {
        var session=sessions.findBySessionIdAndUserId(sessionId,user).orElseThrow(AgentTaskRegistryService::missing);
        var scope=scopeOf(session);var rows=scopedTasks(scope);
        // 终态不进入目录查询，但仍区分本人终态的409与越权/不存在的404。
        var target=tasks.findById(targetTaskId).orElseThrow(AgentTaskRegistryService::missing);
        if(!target.ownedBy(scope))throw missing();
        if(!Set.of("active","paused").contains(target.lifecycle()))throw conflict("终态任务不可控制");
        int paused=(int)rows.stream().filter(t -> "paused".equals(t.lifecycle())).count();
        int delta=switch(action) {
            case "pause","insert" -> { if(!"active".equals(target.lifecycle()))throw conflict("目标不是前台任务");yield 1; }
            case "resume","switch" -> { if(!"paused".equals(target.lifecycle()))throw conflict("目标不是暂停任务");yield session.currentTaskId()==null?-1:0; }
            case "cancel" -> "paused".equals(target.lifecycle())?-1:0;
            default -> throw bad("控制动作无效");
        };
        if(paused+delta>MAX_PAUSED)throw conflict("最多保留五个暂停任务");
        return paused+delta;
    }

    /** 统一404隐藏不存在/越权/旧epoch的差别。 */
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"任务或会话不存在"); }
    /** 版本、容量和状态冲突统一409，拒绝时事务回滚且不更改原资格。 */
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    /** 非法结构由调用方修正，不进入领域或模型链路。 */
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
