// 智能助手模块：定义该模块的数据与行为。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "agent_sessions", uniqueConstraints = @UniqueConstraint(columnNames = "session_id"))
public class AgentSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private UserEntity user;
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
    protected AgentSession() {}
    AgentSession(UserEntity user, String sessionId, AgentMode mode, String traceId) {
        this.user = user; this.sessionId = sessionId; this.mode = mode; this.traceId = traceId;
    }
    public Long getId() { return id; } public UserEntity getUser() { return user; }
    public String getSessionId() { return sessionId; } public AgentMode getMode() { return mode; }
    public String getTraceId() { return traceId; } public boolean isConfirmed() { return confirmed; }
    public Long getCreatedOrderId() { return createdOrderId; }
    public String getActiveWorkflowId() { return activeWorkflowId; }
    void update(AgentMode value, String newTraceId) {
        mode = value; traceId = newTraceId;
        confirmed = false; confirmationConsumed = false;
    }
    void confirm() {
        confirmed = true; confirmationConsumed = false;
    }
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
    public boolean canUseConfirmation(String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        if (!confirmed || confirmationConsumed) return false;
        return canUseAuthorizedCart(workflowId, cartDraftId, addressId, total);
    }
    public boolean canUseAuthorizedCart(String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        String currentWorkflow = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        if (!currentWorkflow.equals(confirmationWorkflowId)) return false;
        return confirmedCartDraftId != null && confirmedCartDraftId.equals(cartDraftId)
                && confirmedAddressId != null && confirmedAddressId.equals(addressId)
                && confirmedTotal != null && total != null && confirmedTotal.compareTo(total) == 0;
    }
    void bindCreatedOrder(Long orderId, String workflowId) {
        createdOrderId = orderId;
        createdOrderWorkflowId = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        createdOrderSettled = false;
    }
    public boolean ownsCreatedOrder(Long orderId, String workflowId) {
        String currentWorkflow = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        return orderId != null && orderId.equals(createdOrderId) && currentWorkflow.equals(createdOrderWorkflowId);
    }
    void consumeConfirmation() { confirmed = false; confirmationConsumed = true; }
    void settleCreatedOrder() { createdOrderSettled = true; }
}
