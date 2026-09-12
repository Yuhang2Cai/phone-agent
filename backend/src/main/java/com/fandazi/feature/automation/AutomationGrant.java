package com.fandazi.feature.automation;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="automation_grants", uniqueConstraints=@UniqueConstraint(columnNames={"user_id","session_id"}))
public class AutomationGrant {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private UserEntity user;
    @Column(name="session_id",nullable=false,length=100) private String sessionId;
    @Column(nullable=false) private Long addressId;
    @Column(nullable=false,precision=12,scale=2) private BigDecimal maxAmount;
    @Column(nullable=false) private Instant expiresAt;
    @Column(name="workflow_id",length=100) private String workflowId;
    @Column(name="cart_draft_id") private Long cartDraftId;
    @Column(name="order_id") private Long orderId;
    @Column(nullable=false) private boolean consumed;
    protected AutomationGrant() {}
    AutomationGrant(UserEntity user,String sessionId,Long addressId,BigDecimal maxAmount,Instant expiresAt){this.user=user;this.sessionId=sessionId;this.addressId=addressId;this.maxAmount=maxAmount;this.expiresAt=expiresAt;}
    public String getSessionId(){return sessionId;} public Instant getExpiresAt(){return expiresAt;}
    public Long getAddressId(){return addressId;} public BigDecimal getMaxAmount(){return maxAmount;} public Long getOrderId(){return orderId;}
    public boolean isConsumed(){return consumed;} public boolean isActiveAt(Instant now){return !consumed && expiresAt.isAfter(now);}
    public boolean canUse(String workflow,Long cart){return workflowId==null || (workflowId.equals(workflow) && cartDraftId.equals(cart));}
    public boolean canPay(String workflow,Long order){return workflowId!=null && workflowId.equals(workflow) && orderId != null && orderId.equals(order);}
    void update(Long address,BigDecimal amount,Instant expires){addressId=address;maxAmount=amount;expiresAt=expires;workflowId=null;cartDraftId=null;orderId=null;consumed=false;}
    void bind(String workflow,Long cart,Long order){workflowId=workflow;cartDraftId=cart;orderId=order;}
    void consume(){consumed=true;}
}
