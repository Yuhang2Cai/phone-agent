// 智能助手模块：定义该模块的数据与行为。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

@Entity @Table(name = "agent_sessions", uniqueConstraints = @UniqueConstraint(columnNames = "session_id"))
public class AgentSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    // 用户实体仅在确实需要资料时懒加载；悲观会话锁不能把所有同用户会话串行到 app_users 行。
    @ManyToOne(optional = false,fetch = FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private UserEntity user;
    // 只读外键用于归属判断和 JPQL 条件；写入仍由 user 关联唯一负责，避免同列双写。
    @Column(name="user_id",insertable=false,updatable=false,nullable=false) private Long ownerId;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AgentMode mode;
    @Column(nullable = false, length = 100) private String traceId;
    @Column(nullable = false) private boolean confirmed;
    @Column(name = "confirmation_workflow_id", length = 100) private String confirmationWorkflowId;
    @Column(name = "confirmed_cart_draft_id") private Long confirmedCartDraftId;
    @Column(name = "confirmed_address_id") private Long confirmedAddressId;
    @Column(name = "confirmed_total", precision = 12, scale = 2) private BigDecimal confirmedTotal;
    @Column(nullable = false) private boolean confirmationConsumed;
    @Column(name = "active_workflow_id", length = 100) private String activeWorkflowId;
    @Column(name = "created_order_id") private Long createdOrderId;
    @Column(name = "created_order_workflow_id", length = 100) private String createdOrderWorkflowId;
    @Column(nullable = false) private boolean createdOrderSettled;
    // 任务与输入链路分别持久化：追问/确认沿用任务标识，新输入仍使用新的 traceId。
    @Column(name = "active_run_id", length = 100) private String activeRunId;
    @Column(name = "active_run_status", length = 30) private String activeRunStatus;
    // 来源顺序与任务版本分离，避免将运行次数或 trace 当作会话输入顺序。
    @Column(name="conversation_epoch",length=100) private String conversationEpoch;
    @Column(name="conversation_turn_sequence",nullable=false) private long conversationTurnSequence;
    @Column(name="context_protocol_version",length=10) private String contextProtocolVersion;
    // AG03焦点与旧活动run字段分开；仅2.2任务目录事务可以推进调度版本。
    @Column(name="current_conversation_task_id",length=100) private String currentConversationTaskId;
    @Column(name="task_focus_version",nullable=false) private long taskFocusVersion;
    @Column(name="task_event_sequence",nullable=false) private long taskEventSequence;
    // 新协议确认摘要显式归属task；旧摘要为空归属，不被后台控制误清。
    @Column(name="pending_confirmation_task_id",length=100) private String pendingConfirmationTaskId;
    // 2.2明确确认只绑定用户实际确认的turn；短回复和新输入不能继承泛化会话confirmed。
    @Column(name="confirmed_turn_id",length=100) private String confirmedTurnId;
    // 只保存 Spring 已重查的待确认动作；跨输入恢复时用于绑定用户看到的资源、金额、规则和正文。
    @Convert(converter = StringMapJsonConverter.class)
    @Column(name = "pending_confirmation", columnDefinition = "TEXT")
    private java.util.Map<String, Object> pendingConfirmation;
// 初始化对象依赖与默认状态。
    protected AgentSession() {}
// 初始化对象依赖与默认状态。
    AgentSession(UserEntity user, String sessionId, AgentMode mode, String traceId) {
        this.user = user; this.ownerId=user.getId(); this.sessionId = sessionId; this.mode = mode; this.traceId = traceId;
        activeRunId = java.util.UUID.randomUUID().toString();
        activeRunStatus = "running";
    }
// 获取id。
    // 获取用户。
    public Long getId() { return id; } public UserEntity getUser() { return user; }
    /** 返回会话表中已持久化的用户外键；调用它不会加载或锁定共享用户实体。 */
    public Long ownerId() { return ownerId; }
// 获取会话、id。
    // 获取mode。
    public String getSessionId() { return sessionId; } public AgentMode getMode() { return mode; }
// 获取trace、id。
    // 判断confirmed。
    public String getTraceId() { return traceId; } public boolean isConfirmed() { return confirmed; }
// 获取created、订单、id。
    public Long getCreatedOrderId() { return createdOrderId; }
// 获取active、工作流、id。
    public String getActiveWorkflowId() { return activeWorkflowId; }
    /** 返回当前可暂停任务标识，不与旧外卖工作流授权字段混用。 */
    public String getActiveRunId() { return activeRunId; }
    /** 内部工具只允许当前正在执行的轮次调用，暂停或终态不能继续产生动作。 */
    public String getActiveRunStatus() { return activeRunStatus; }
    /** 仅在行锁内初始化 epoch；旧会话不冒认已有完整历史。 */
    String ensureConversationEpoch() {
        if(conversationEpoch==null)conversationEpoch=java.util.UUID.randomUUID().toString();
        return conversationEpoch;
    }
    /** 只读返回epoch，目录查询不能通过ensure方法补写历史状态。 */
    String conversationEpoch() { return conversationEpoch; }
    /** 返回当前任务身份；旧协议不会自动分配会话任务。 */
    String currentTaskId() { return currentConversationTaskId; }
    /** 返回焦点CAS版本，普通停点保存不随意递增。 */
    long focusVersion() { return taskFocusVersion; }
    /** 返回已纳入目录投影的事件水位；TL-03装配outbox前为0。 */
    long taskEventSequence() { return taskEventSequence; }
    /** 在控制prepared事务推进焦点版本，commit只绑定身份而不再递增。 */
    void advanceTaskFocus() {taskFocusVersion++;}
    /** 控制commit/abort设置已核验焦点；prepared已推进版本，不能二次推进。 */
    void commitTaskFocus(String taskId) {currentConversationTaskId=taskId;}
    /** 公开事件与目录共用事务推进水位；事件写入失败必须回滚此序号。 */
    long nextTaskEvent() {return ++taskEventSequence;}
    /** 2.2确认摘要必须绑定当前任务，不能沿用泛化会话确认。 */
    void awaitTaskConfirmation(String taskId,java.util.Map<String,Object> summary) {
        pendingConfirmationTaskId=taskId;awaitConfirmation(summary);
    }
    /** 只清除受影响task摘要；取消其他paused任务不碰前台trace/确认。 */
    void clearTaskConfirmation(java.util.Set<String> affected) {
        if(pendingConfirmationTaskId!=null&&affected.contains(pendingConfirmationTaskId)) {
            pendingConfirmationTaskId=null;pendingConfirmation=null;confirmed=false;confirmationConsumed=true;
        }
    }
    /** 普通可执行输入只更新自己的trace；不调用旧update而误清确认/重分配run。 */
    void bindTaskTrace(String value) {traceId=value;}
    /** 返回摘要任务归属，不能用会话中任意旧确认替代。 */
    String pendingConfirmationTaskId(){return pendingConfirmationTaskId;}
    /** 已核验用户明确确认后绑定当前turn；不改变grant期限或金额。 */
    void confirmTaskTurn(String turnId){confirm();confirmedTurnId=turnId;}
    /** 业务写必须由同一个已确认turn发起，其他普通输入不借用本次确认。 */
    boolean taskTurnConfirmed(String turnId){return confirmed&&!confirmationConsumed&&Objects.equals(confirmedTurnId,turnId);}
    /** 在会话锁内设置已核验焦点；只有焦点实际变化才推进版本。 */
    void bindTaskFocus(String taskId) {
        if(!java.util.Objects.equals(currentConversationTaskId,taskId))taskFocusVersion++;
        currentConversationTaskId=taskId;
    }
    /** 2.2新会话不伪装成旧运行；旧字段仅按当前任务run显式同步供既有投影兼容。 */
    void bindTaskRun(String runId,String status) { activeRunId=runId;activeRunStatus=status; }
    /** 新会话或旧run已终态时显式进入2.2；清除旧停点资格，来源epoch与历史保留。 */
    void enterTaskProtocol(String newTraceId) {
        contextProtocolVersion="2.2";ensureConversationEpoch();traceId=newTraceId;
        // 不复活旧run，也不携带终态前留下的确认摘要；已提交订单等事实字段不改写。
        bindTaskRun(null,null);pendingConfirmation=null;confirmed=false;confirmationConsumed=true;
    }
    /** 递增与来源写入共用事务，失败回滚不消耗已签发顺序。 */
    long nextConversationTurn() { return ++conversationTurnSequence; }
    /** 历史活动任务缺版本时按 B2 的 2.0 完成，不随部署配置升级。 */
    public String getContextProtocolVersion() { return contextProtocolVersion==null ? "2.0" : contextProtocolVersion; }
    /** 新任务建立前固定版本，仅供持有行锁的会话服务使用。 */
    void bindContextProtocol(String version) { contextProtocolVersion=version; }
// 更新当前数据。
    void update(AgentMode value, String newTraceId) {
        // 仅等待确认才能继承上轮授权摘要；普通新任务或补充输入不能带走旧动作的确认。
        if (!"waiting_confirmation".equals(activeRunStatus)) pendingConfirmation = null;
        // 只有等待输入或确认才是恢复任务；已结束或历史空字段须分配新的任务。
        if (activeRunId == null || !("waiting_input".equals(activeRunStatus) || "waiting_confirmation".equals(activeRunStatus))) {
            activeRunId = java.util.UUID.randomUUID().toString();
        }
        activeRunStatus = "running";
        mode = value; traceId = newTraceId;
        confirmed = false; confirmationConsumed = false;
    }

    /** 仅更新匹配的任务，避免旧响应覆盖同一会话中更晚任务的暂停/终态。 */
    void finishRun(String runId, AgentResult.Status status) {
        if (!java.util.Objects.equals(activeRunId, runId)) return;
        activeRunStatus = switch (status) {
            case NEEDS_INPUT -> "waiting_input";
            case AWAITING_CONFIRMATION -> "waiting_confirmation";
            case COMPLETED -> "succeeded";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
        };
    }
// 确认当前数据。
    void confirm() {
        confirmed = true; confirmationConsumed = false;
    }
// 处理 `bindAuthorizedCart` 相关逻辑。
    void bindAuthorizedCart(String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        if (workflowId == null || workflowId.isBlank() || cartDraftId == null || addressId == null || total == null) {
            throw new com.fandazi.feature.DomainException("授权购物车不完整");
        }
        confirmationWorkflowId = workflowId;
        confirmedCartDraftId = cartDraftId;
        confirmedAddressId = addressId;
        confirmedTotal = total;
        activeWorkflowId = workflowId;
    }
// 处理 `bindWorkflow` 相关逻辑。
    void bindWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) workflowId = traceId;
        if (activeWorkflowId == null || activeWorkflowId.equals(workflowId)) {
            activeWorkflowId = workflowId;
            return;
        }
        if (!confirmationConsumed && confirmationWorkflowId != null && !confirmationWorkflowId.equals(workflowId)) {
            throw new com.fandazi.feature.DomainException("当前授权已绑定其他工作流");
        }
        if (createdOrderId == null || confirmationConsumed || createdOrderSettled || mode == AgentMode.CONFIRM) {
            activeWorkflowId = workflowId;
            createdOrderId = null;
            createdOrderWorkflowId = null;
            confirmationWorkflowId = null;
            confirmedCartDraftId = null;
            confirmedAddressId = null;
            confirmedTotal = null;
            return;
        }
        throw new com.fandazi.feature.DomainException("当前会话已有未完成订单工作流");
    }
// 判断能否use、confirmation。
    public boolean canUseConfirmation(String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        if (!confirmed || confirmationConsumed) return false;
        return canUseAuthorizedCart(workflowId, cartDraftId, addressId, total);
    }
// 判断能否use、authorized、购物车。
    public boolean canUseAuthorizedCart(String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        String currentWorkflow = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        if (!currentWorkflow.equals(confirmationWorkflowId)) return false;
        return confirmedCartDraftId != null && confirmedCartDraftId.equals(cartDraftId)
                && confirmedAddressId != null && confirmedAddressId.equals(addressId)
                && confirmedTotal != null && total != null && confirmedTotal.compareTo(total) == 0;
    }
// 处理 `bindCreatedOrder` 相关逻辑。
    void bindCreatedOrder(Long orderId, String workflowId) {
        createdOrderId = orderId;
        createdOrderWorkflowId = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        createdOrderSettled = false;
    }
// 处理 `ownsCreatedOrder` 相关逻辑。
    public boolean ownsCreatedOrder(Long orderId, String workflowId) {
        String currentWorkflow = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        return orderId != null && orderId.equals(createdOrderId) && currentWorkflow.equals(createdOrderWorkflowId);
    }
// 处理 `consumeConfirmation` 相关逻辑。
    void consumeConfirmation() { confirmed = false; confirmationConsumed = true; }
    /** 保存已经重查过的确认摘要；调用方必须持有会话行锁并核对活动任务。 */
    void awaitConfirmation(java.util.Map<String, Object> summary) { pendingConfirmation = new java.util.LinkedHashMap<>(summary); }
    /** 返回待确认事实供写工具比对，缺少摘要时不能仅凭泛化确认句写业务。 */
    public java.util.Map<String, Object> getPendingConfirmation() { return pendingConfirmation == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(pendingConfirmation); }
// 处理 `settleCreatedOrder` 相关逻辑。
    void settleCreatedOrder() { createdOrderSettled = true; }
}
