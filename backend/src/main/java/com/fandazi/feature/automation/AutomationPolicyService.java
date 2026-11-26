// 自动下单授权模块：集中处理业务规则与状态变更。
package com.fandazi.feature.automation;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class AutomationPolicyService {
    private final AutomationPolicyRepository policies; private final AutomationGrantRepository grants; private final UserRepository users; private final AddressService addresses;
// 初始化对象依赖与默认状态。
    public AutomationPolicyService(AutomationPolicyRepository policies,AutomationGrantRepository grants,UserRepository users,AddressService addresses){this.policies=policies;this.grants=grants;this.users=users;this.addresses=addresses;}
// 处理 `upsert` 相关逻辑。
    @Transactional public AutomationPolicy upsert(Long userId,boolean enabled,Long addressId,BigDecimal maxAmount){validate(userId,addressId,maxAmount);var existing=policies.findByUserId(userId);if(existing.isPresent()){existing.get().update(enabled,addressId,maxAmount);return existing.get();}return policies.save(new AutomationPolicy(users.findById(userId).orElseThrow(()->new DomainException("用户不存在")),enabled,addressId,maxAmount));}
// 处理 `grantForTask` 相关逻辑。
    @Transactional public AutomationGrant grantForTask(Long userId,String sessionId,Long addressId,BigDecimal maxAmount,Instant expiresAt){validate(userId,addressId,maxAmount);if(sessionId==null||sessionId.isBlank())throw new DomainException("会话不能为空");if(expiresAt==null)throw new DomainException("授权有效期不能为空");var existing=grants.findByUserIdAndSessionIdForUpdate(userId,sessionId);if(existing.isPresent()){existing.get().update(addressId,maxAmount,expiresAt);return existing.get();}return grants.save(new AutomationGrant(users.findById(userId).orElseThrow(()->new DomainException("用户不存在")),sessionId,addressId,maxAmount,expiresAt));}
// 处理 `policy` 相关逻辑。
    // 处理 `grant` 相关逻辑。
    @Transactional(readOnly=true) public AutomationPolicy policy(Long userId){return policies.findByUserId(userId).orElse(null);} @Transactional(readOnly=true) public AutomationGrant grant(Long userId,String sessionId){return grants.findByUserIdAndSessionId(userId,sessionId).orElse(null);}
// 处理 `lockedGrant` 相关逻辑。
    @Transactional public AutomationGrant lockedGrant(Long userId,String sessionId){return grants.findByUserIdAndSessionIdForUpdate(userId,sessionId).orElse(null);}
// 处理 `bindTaskGrant` 相关逻辑。
    @Transactional public void bindTaskGrant(Long userId,String sessionId,String workflowId,Long cartDraftId,Long orderId){var grant=lockedGrant(userId,sessionId);if(grant!=null)grant.bind(workflowId,cartDraftId,orderId);}
// 处理 `consumeTaskGrant` 相关逻辑。
    @Transactional public void consumeTaskGrant(Long userId,String sessionId,String workflowId,Long orderId){var grant=lockedGrant(userId,sessionId);if(grant!=null&&grant.canPay(workflowId,orderId))grant.consume();}
// 处理 `revokeTaskGrant` 相关逻辑。
    @Transactional public void revokeTaskGrant(Long userId,String sessionId){var grant=lockedGrant(userId,sessionId);if(grant!=null)grant.revoke();}
// 校验当前数据。
    private void validate(Long userId,Long addressId,BigDecimal maxAmount){if(!addresses.owned(userId,addressId).isDefaultAddress())throw new DomainException("自动授权必须使用当前默认地址");if(maxAmount==null||maxAmount.signum()<=0)throw new DomainException("金额上限无效");}
}
