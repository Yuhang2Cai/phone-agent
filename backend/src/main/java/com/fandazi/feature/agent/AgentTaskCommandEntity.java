// 持久控制命令模块：保存请求去重、受影响停点、交接决定及有限重对账状态，不包含业务授权。
package com.fandazi.feature.agent;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** 所有状态修改在session→task→command锁序内；网络超时不能直接变为aborted。 */
@Entity @Table(name="agent_task_commands")
public class AgentTaskCommandEntity {
    // 返回指令仅来自明确原文；单独索引用于完成/重启后有界恢复，不扫描所有历史命令JSON。
    @Column(name="return_status",length=30) private String returnStatus;
    @Column(name="return_command_id",length=100) private String returnCommandId;
    @Id @Column(name="command_id",length=100) private String id;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100,updatable=false) private String sessionId;
    @Column(nullable=false,length=100,updatable=false) private String epoch;
    @Column(name="request_id",nullable=false,length=100,updatable=false) private String requestId;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Column(nullable=false,length=30) private String action;
    @Column(name="target_task_id",nullable=false,length=100) private String targetTaskId;
    @Column(name="source_message_id",nullable=false,length=100) private String sourceMessageId;
    @Column(nullable=false,length=30) private String state;
    @Column(name="reconcile_status",nullable=false,length=30) private String reconcileStatus;
    @Column(name="reason_code",length=100) private String reasonCode;
    @Column(name="focus_version",nullable=false) private long focusVersion;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="state_json",nullable=false,columnDefinition="TEXT") private Map<String,Object> details;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="ack_json",columnDefinition="TEXT") private Map<String,Object> ack;
    @Column(name="dispatch_count",nullable=false) private int dispatchCount;
    @Column(name="first_dispatched_at") private Instant firstDispatchedAt;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="lease_owner",length=100) private String leaseOwner;
    @Column(name="lease_until") private Instant leaseUntil;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    /** 仅供JPA恢复命令行。 */
    protected AgentTaskCommandEntity() {}
    /** requestId在完整scope唯一；新命令身份由Spring生成，不能由模型指定。 */
    AgentTaskCommandEntity(AgentTaskRegistryService.Scope scope,String requestId,String fingerprint,String action,
            String target,String source,long focus,Map<String,Object> details) {
        id=UUID.randomUUID().toString();userId=scope.userId();sessionId=scope.sessionId();epoch=scope.epoch();
        this.requestId=requestId;this.fingerprint=fingerprint;this.action=action;targetTaskId=target;
        sourceMessageId=source;focusVersion=focus;this.details=new LinkedHashMap<>(details);
        state="prepared";reconcileStatus="idle";createdAt=Instant.now();nextAttemptAt=createdAt;
    }
    /** 返回不可变命令身份，网络重试始终复用。 */
    public String id() {return id;}
    /** 插入预备事务同时登记来源，任务未成功前不恢复任何焦点或业务。 */
    void registerReturn(){returnStatus="registered";}
    /** 返回当前登记/已预备/完成/失效状态，重复处理不得签第二个返回命令。 */
    String returnStatus(){return returnStatus;}
    /** 返回持久交接命令引用，不从最近任务猜目标。 */
    String returnCommandId(){return returnCommandId;}
    /** 返回控制一旦预备即固定命令ID，故障后只推进同一命令。 */
    void prepareReturn(String id){returnCommandId=id;returnStatus="prepared";}
    /** 条件已变更时标失效；成功时标完成，均不能再次登记自动恢复。 */
    void finishReturn(String status){returnStatus=status;}
    /** 返回命令归属，不接受模型输入覆写。 */
    AgentTaskRegistryService.Scope scope() {return new AgentTaskRegistryService.Scope(userId,sessionId,epoch);}
    /** 返回请求指纹以拒绝同键异文。 */
    String fingerprint() {return fingerprint;}
    /** 返回明确生命周期动作，不作为业务工具名称使用。 */
    String action() {return action;}
    /** 返回用户明确指定目标或Spring预分配的新目标。 */
    String target() {return targetTaskId;}
    /** 返回可信输入/按钮来源。 */
    String source() {return sourceMessageId;}
    /** 返回交接状态；prepared不表示已经切换焦点。 */
    String state() {return state;}
    /** 返回停止自动派发/待核查状态，与业务执行事实分开。 */
    String reconcileStatus() {return reconcileStatus;}
    /** 返回预备时冻结的调度版本，commit不得再次递增。 */
    long focusVersion() {return focusVersion;}
    /** 返回内部前后停点及目标清单；绝不直接作为公开事件发送。 */
    Map<String,Object> details() {return details;}
    /** 返回当前派发次数，超过上限只查询事实而非继续派发。 */
    int dispatchCount() {return dispatchCount;}
    /** 判断终结交接决定，迟到ack不能反转它。 */
    boolean terminal() {return Set.of("committed","aborted","rejected").contains(state);}
    /** 判断派发租约是否仍被占用；过期只允许接管查询/交接，不重执业务。 */
    boolean leased(Instant now) {return leaseUntil!=null&&leaseUntil.isAfter(now);}
    /** 计算六次或60秒上限，先触发者停止派发；时间从首次实际派发开始。 */
    boolean exhausted(Instant now) {return dispatchCount>=6||(firstDispatchedAt!=null&&!now.isBefore(firstDispatchedAt.plusSeconds(60)));}
    /** 只在允许时间认领；后台退避不会因连续轮询而跳过计划等待。 */
    boolean due(Instant now) {return nextAttemptAt==null||!nextAttemptAt.isAfter(now);}
    /** 在短事务中认领同命令派发，网络调用必须在事务提交后进行。 */
    void claim(String owner,Instant now) {
        // 数据库时间精度低于Instant；向下截断避免60秒边界被持久化四舍五入推迟。
        if(firstDispatchedAt==null)firstDispatchedAt=now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        dispatchCount++;leaseOwner=owner;leaseUntil=now.plusSeconds(10);reconcileStatus="retrying";
    }
    /** 只读对账也使用跨进程租约，但不计为派发且永不重新执行控制/业务。 */
    void claimLookup(String owner,Instant now) {leaseOwner=owner;leaseUntil=now.plusSeconds(10);nextAttemptAt=now.plusSeconds(5);}
    /** 读取失败/未知保留原原因，至少五秒后才能后台再查，避免无限紧轮询。 */
    void finishLookup(Instant now) {leaseOwner=null;leaseUntil=null;nextAttemptAt=now.plusSeconds(5);}
    /** 核对派发者，旧租约响应不能覆盖后续安全退出决定。 */
    boolean leaseOwned(String owner) {return Objects.equals(leaseOwner,owner);}
    /** 失败仅更新对账原因与退避；不宣称控制完成或原写入未发生。 */
    void retry(String reason,Instant now) {
        reasonCode=reason;leaseOwner=null;leaseUntil=null;
        nextAttemptAt=now.plusSeconds(dispatchCount<=1?1:dispatchCount==2?2:5);
        if(exhausted(now)){reconcileStatus="needs_attention";reasonCode="reconcile_limit";}
    }
    /** 停止自动派发，保留命令与执行栅栏供显式核查。 */
    void attention(String reason) {reconcileStatus="needs_attention";reasonCode=reason;leaseOwner=null;leaseUntil=null;}
    /** 保存安全回执；acknowledged阶段必须持久，再由另一短事务提交焦点。 */
    void acknowledge(Map<String,Object> value) {ack=new LinkedHashMap<>(value);state="agent_acknowledged";leaseOwner=null;leaseUntil=null;}
    /** 读取持久回执，崩溃后不重复要求Agent产生新停点。 */
    Map<String,Object> ack() {return ack;}
    /** 提交或退出必须有服务层核验证据；此方法不自动恢复旧授权或回退版本。 */
    void finish(String value) {state=value;reconcileStatus="idle";reasonCode=null;leaseOwner=null;leaseUntil=null;}
    /** 先保存不可反转的退出决定，迟到正常回执只能按aborting处理。 */
    void beginAbort() {state="aborting";ack=null;reconcileStatus="retrying";leaseOwner=null;leaseUntil=null;nextAttemptAt=Instant.now();}
    /** 公开命令只显示控制元数据，不包含检查点正文、请求指纹、租约或任何凭证。 */
    Map<String,Object> projection() {
        var out=new LinkedHashMap<String,Object>();out.put("schemaVersion","2.2");out.put("commandId",id);out.put("action",action);
        out.put("state",state);out.put("reconcileStatus",reconcileStatus);out.put("reasonCode",reasonCode);
        out.put("focusVersion",focusVersion);out.put("affectedTaskIds",details.get("affectedTaskIds"));return out;
    }
}
