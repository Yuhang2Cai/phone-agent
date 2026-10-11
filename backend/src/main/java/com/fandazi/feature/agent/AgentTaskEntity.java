// 会话任务持久实体：Spring保存归属、生命周期和公开停点；执行检查点正文仍由Agent管理。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** 所有变更必须在会话行锁下发生；任务ID不等于DAG节点ID，不携带授权凭证。 */
@Entity @Table(name="agent_conversation_tasks")
public class AgentTaskEntity {
    @Id @Column(name="conversation_task_id",length=100) private String id;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100,updatable=false) private String sessionId;
    @Column(nullable=false,length=100,updatable=false) private String epoch;
    @Column(name="task_kind",nullable=false,length=30) private String taskKind;
    @Column(nullable=false,length=50) private String intent;
    @Column(nullable=false,length=120) private String title;
    @Column(nullable=false,length=30) private String lifecycle;
    @Column(name="task_version",nullable=false) private long taskVersion;
    @Column(name="execution_generation",nullable=false) private long executionGeneration;
    @Column(name="stop_kind",nullable=false,length=30) private String stopKind;
    @Column(name="awaiting_field",length=100) private String awaitingField;
    @Column(name="goal_id",length=100) private String goalId;
    @Column(name="active_run_id",length=100) private String activeRunId;
    @Column(name="last_run_id",length=100) private String lastRunId;
    @Column(name="active_scope_key",length=255,unique=true) private String activeScopeKey;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="last_paused_at") private Instant lastPausedAt;
    @Column(name="pending_command_id",length=100) private String pendingCommandId;
    // 原订单来自真实领域创建/读取，控制撤销确认时仍保留停点资源，禁止重新新建订单。
    @Column(name="pending_payment_order_id",length=100) private String pendingPaymentOrderId;

    /** 仅供JPA反序列化；外部不能自行构造或指定未来任务ID。 */
    protected AgentTaskEntity() {}

    /** 在会话锁内创建已核验的新目标；任务初始无run，下一次受理再分配。 */
    AgentTaskEntity(AgentTaskRegistryService.Scope scope,String kind,String intent,String title) {
        id=UUID.randomUUID().toString();userId=scope.userId();sessionId=scope.sessionId();epoch=scope.epoch();
        taskKind=kind;this.intent=intent;this.title=title;lifecycle="active";stopKind="running";
        taskVersion=1;executionGeneration=1;activeScopeKey=scope.key();createdAt=Instant.now();
    }
    /** 仅恢复Spring预分配且持久命令已冻结的新目标身份；不接受公开请求/model指定ID。 */
    static AgentTaskEntity reserved(AgentTaskRegistryService.Scope scope,String kind,String intent,String title,String trustedId) {
        var task=new AgentTaskEntity(scope,kind,intent,title);task.id=trustedId;return task;
    }

    /** 逐项检查完整可信归属；其他用户或旧epoch不得观察任务存在性。 */
    boolean ownedBy(AgentTaskRegistryService.Scope scope) {
        return userId.equals(scope.userId())&&sessionId.equals(scope.sessionId())&&epoch.equals(scope.epoch());
    }
    /** 返回服务端分配的会话任务身份，不能作为访问凭证。 */
    public String id() { return id; }
    /** 返回生命周期，不能据此推断订单、退款等业务事实是否已提交。 */
    public String lifecycle() { return lifecycle; }
    /** 返回目录CAS版本，不代替动作版本或执行代次。 */
    public long version() { return taskVersion; }
    /** 返回工具执行代次；控制预备时推进使旧轮次失效。 */
    public long generation() { return executionGeneration; }
    /** 返回当前未终结run；合法推荐跟进时允许为空。 */
    public String activeRunId() { return activeRunId; }
    /** 返回最后一次run，仅用于归属历史，不表示其可被重开。 */
    public String lastRunId() { return lastRunId; }
    /** 返回任务类型，决定run成功是否仍等待合法跟进。 */
    public String kind() { return taskKind; }
    /** 返回待交接命令；有值时任何普通轮次都不得取得执行资格。 */
    String pendingCommand() {return pendingCommandId;}
    /** 返回原支付停点资源，只用于内部重准备，不进入模型可改写字段。 */
    String pendingPaymentOrderId(){return pendingPaymentOrderId;}
    /** 真实领域结果绑定支付对象；同一任务不得在等待支付期间悄悄替换原订单。 */
    void bindPaymentOrder(String orderId){
        if(pendingPaymentOrderId!=null&&!pendingPaymentOrderId.equals(orderId))throw new ToolBoundaryException("CONFLICT","任务已有不同支付停点");pendingPaymentOrderId=orderId;
    }
    /** 返回停点类型供内部恢复核验，调度暂停不把原停点改成running。 */
    String stopKind(){return stopKind;}
    /** 返回最近暂停时间，仅影响目录显示排序，不能用于语义目标猜测。 */
    Instant pausedAt() { return lastPausedAt; }
    /** 返回创建时间，作为稳定展示排序的次级键。 */
    Instant createdAt() { return createdAt; }

    /** 绑定新run；首次分配保持初始代次，推荐终态后的跟进必须使用新代次。 */
    void startRun(String runId) {
        if(lastRunId!=null)executionGeneration++;
        activeRunId=runId;lastRunId=runId;stopKind="running";awaitingField=null;goalId=null;taskVersion++;
    }

    /** 提交当前run停点；终态选择保留任务，其他终态清空可执行焦点资格。 */
    void recordStop(String status,String awaitingField,String goalId) {
        taskVersion++;this.awaitingField=awaitingField;this.goalId=goalId;
        if(Set.of("running","waiting_input","waiting_confirmation").contains(status)) { stopKind=status;return; }
        // 推荐run成功后任务仍active，后续请求新建同task run；旧run的成功记录不可改写。
        activeRunId=null;
        if("succeeded".equals(status)&&"selection".equals(taskKind))stopKind="awaiting_followup";
        else { lifecycle="succeeded".equals(status)?"completed":status;stopKind="terminal";activeScopeKey=null; }
    }
    /** 预备只推进版本/代次并隔离执行，保留原生命周期与业务停点直到安全回执。 */
    void prepareControl(String commandId) {pendingCommandId=commandId;taskVersion++;executionGeneration++;}
    /** 提交明确调度状态；unique active key由同session事务先释放旧前台再设置目标。 */
    void commitLifecycle(String value,AgentTaskRegistryService.Scope scope) {
        lifecycle=value;pendingCommandId=null;activeScopeKey="active".equals(value)?scope.key():null;
        if("paused".equals(value))lastPausedAt=Instant.now();
        if(Set.of("completed","cancelled","failed").contains(value)){activeRunId=null;stopKind="terminal";}
    }
    /** 安全退出保持原状态/停点，签后续新代次；绝不回退版本或恢复旧确认。 */
    void abortControl() {pendingCommandId=null;taskVersion++;executionGeneration++;}

    /** 只构造公开字段白名单；目录不包含slots、确认凭证或业务正文。 */
    Map<String,Object> projection() {
        var result=new LinkedHashMap<String,Object>();
        result.put("conversationTaskId",id);result.put("taskKind",taskKind);result.put("intent",intent);
        result.put("lifecycle",lifecycle);result.put("taskVersion",taskVersion);result.put("executionGeneration",executionGeneration);
        result.put("title",title);var stop=new LinkedHashMap<String,Object>();
        stop.put("kind",stopKind);stop.put("awaitingField",awaitingField);stop.put("goalId",goalId);result.put("stopPoint",stop);
        result.put("activeRunId",activeRunId);result.put("lastRunId",lastRunId);
        result.put("availableActions",switch(lifecycle) {case "active" -> List.of("pause","cancel");
            case "paused" -> List.of("resume","cancel");default -> List.of();});
        return result;
    }
}
