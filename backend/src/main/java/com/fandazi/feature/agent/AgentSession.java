package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;

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
        confirmationWorkflowId = null; confirmedCartDraftId = null; confirmedAddressId = null;
    }
    void confirm() {
        confirmed = true; confirmationConsumed = false;
        confirmationWorkflowId = null; confirmedCartDraftId = null; confirmedAddressId = null;
    }
    void bindWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) workflowId = traceId;
        if (activeWorkflowId == null || activeWorkflowId.equals(workflowId)) {
            activeWorkflowId = workflowId;
            return;
        }
        if (createdOrderId == null || confirmationConsumed || createdOrderSettled || mode == AgentMode.CONFIRM) {
            activeWorkflowId = workflowId;
            createdOrderId = null;
            createdOrderWorkflowId = null;
            confirmationWorkflowId = null;
            confirmedCartDraftId = null;
            confirmedAddressId = null;
            return;
        }
        throw new com.fandazi.feature.DomainException("当前会话已有未完成订单工作流");
    }
    public boolean canUseConfirmation(String workflowId, Long cartDraftId, Long addressId) {
        if (!confirmed || confirmationConsumed) return false;
        String currentWorkflow = workflowId == null || workflowId.isBlank() ? traceId : workflowId;
        if (confirmationWorkflowId == null) confirmationWorkflowId = currentWorkflow;
        if (!confirmationWorkflowId.equals(currentWorkflow)) return false;
        if (confirmedCartDraftId == null) {
            confirmedCartDraftId = cartDraftId; confirmedAddressId = addressId;
        }
        return confirmedCartDraftId.equals(cartDraftId) && confirmedAddressId.equals(addressId);
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
