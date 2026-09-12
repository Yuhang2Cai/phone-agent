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
    public AutomationPolicyService(AutomationPolicyRepository policies,AutomationGrantRepository grants,UserRepository users,AddressService addresses){this.policies=policies;this.grants=grants;this.users=users;this.addresses=addresses;}
    @Transactional public AutomationPolicy upsert(Long userId,boolean enabled,Long addressId,BigDecimal maxAmount){validate(userId,addressId,maxAmount);var existing=policies.findByUserId(userId);if(existing.isPresent()){existing.get().update(enabled,addressId,maxAmount);return existing.get();}return policies.save(new AutomationPolicy(users.findById(userId).orElseThrow(()->new DomainException("用户不存在")),enabled,addressId,maxAmount));}
    @Transactional public AutomationGrant grantForTask(Long userId,String sessionId,Long addressId,BigDecimal maxAmount,Instant expiresAt){validate(userId,addressId,maxAmount);if(sessionId==null||sessionId.isBlank())throw new DomainException("会话不能为空");if(expiresAt==null)throw new DomainException("授权有效期不能为空");var existing=grants.findByUserIdAndSessionId(userId,sessionId);if(existing.isPresent()){existing.get().update(addressId,maxAmount,expiresAt);return existing.get();}return grants.save(new AutomationGrant(users.findById(userId).orElseThrow(()->new DomainException("用户不存在")),sessionId,addressId,maxAmount,expiresAt));}
    @Transactional(readOnly=true) public AutomationPolicy policy(Long userId){return policies.findByUserId(userId).orElse(null);} @Transactional(readOnly=true) public AutomationGrant grant(Long userId,String sessionId){return grants.findByUserIdAndSessionId(userId,sessionId).orElse(null);}
    @Transactional public void bindTaskGrant(Long userId,String sessionId,String workflowId,Long cartDraftId,Long orderId){var grant=grants.findByUserIdAndSessionId(userId,sessionId).orElse(null);if(grant!=null)grant.bind(workflowId,cartDraftId,orderId);}
    @Transactional public void consumeTaskGrant(Long userId,String sessionId,String workflowId,Long orderId){var grant=grants.findByUserIdAndSessionId(userId,sessionId).orElse(null);if(grant!=null&&grant.canPay(workflowId,orderId))grant.consume();}
    private void validate(Long userId,Long addressId,BigDecimal maxAmount){addresses.owned(userId,addressId);if(maxAmount==null||maxAmount.signum()<=0)throw new DomainException("金额上限无效");}
}
