// 自动下单授权模块：定义该模块的数据与行为。
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
// 初始化对象依赖与默认状态。
    protected AutomationGrant() {}
// 初始化对象依赖与默认状态。
    AutomationGrant(UserEntity user,String sessionId,Long addressId,BigDecimal maxAmount,Instant expiresAt){this.user=user;this.sessionId=sessionId;this.addressId=addressId;this.maxAmount=maxAmount;this.expiresAt=expiresAt;}
// 获取会话、id。
    // 获取expires、at。
    public String getSessionId(){return sessionId;} public Instant getExpiresAt(){return expiresAt;}
// 获取地址、id。
    // 获取max、amount。
    // 获取订单、id。
    public Long getAddressId(){return addressId;} public BigDecimal getMaxAmount(){return maxAmount;} public Long getOrderId(){return orderId;}
// 判断consumed。
    // 判断active、at。
    public boolean isConsumed(){return consumed;} public boolean isActiveAt(Instant now){return !consumed && expiresAt.isAfter(now);}
// 判断能否use。
    public boolean canUse(String workflow,Long cart){return workflowId==null || (workflowId.equals(workflow) && cartDraftId.equals(cart));}
// 判断能否pay。
    public boolean canPay(String workflow,Long order){return workflowId!=null && workflowId.equals(workflow) && orderId != null && orderId.equals(order);}
// 更新当前数据。
    void update(Long address,BigDecimal amount,Instant expires){addressId=address;maxAmount=amount;expiresAt=expires;workflowId=null;cartDraftId=null;orderId=null;consumed=false;}
// 处理 `bind` 相关逻辑。
    void bind(String workflow,Long cart,Long order){workflowId=workflow;cartDraftId=cart;orderId=order;}
// 处理 `consume` 相关逻辑。
    void consume(){consumed=true;}
// 处理 `revoke` 相关逻辑。
    void revoke(){consumed=true; expiresAt=Instant.EPOCH;}
}
