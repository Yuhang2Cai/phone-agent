// 订单授权服务：在创建订单前核验授权范围。
package com.fandazi.feature.automation;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.agent.AgentMode;
import com.fandazi.feature.agent.AgentSession;
import com.fandazi.feature.cart.CartQuote;
import com.fandazi.feature.cart.CartService;
import com.fandazi.seed.SeedAddressMetadataRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class OrderAuthorizationService {
    public record AuthorizedOrder(CartQuote quote, Long addressId) {}
    private final AutomationPolicyService policies; private final CartService carts; private final AddressService addresses; private final SeedAddressMetadataRepository addressMetadata;
    public OrderAuthorizationService(AutomationPolicyService policies,CartService carts,AddressService addresses,SeedAddressMetadataRepository addressMetadata){this.policies=policies;this.carts=carts;this.addresses=addresses;this.addressMetadata=addressMetadata;}
    @Transactional public AuthorizedOrder authorize(Long userId,AgentSession session,Long cartId,Long addressId){ return authorize(userId,session,cartId,addressId,session.getTraceId()); }
    @Transactional public AuthorizedOrder authorize(Long userId,AgentSession session,Long cartId,Long addressId,String workflowId){
        if(!session.getUser().getId().equals(userId))throw new DomainException("会话不属于当前用户"); var address=addresses.owned(userId,addressId);
        if(addressMetadata.findByAddressId(addressId).map(m->!m.isValid()).orElse(false))throw new DomainException("地址已失效");
        CartQuote quote=carts.validate(userId,cartId); if(session.getMode()==AgentMode.CONFIRM){if(!session.canUseConfirmation(workflowId,cartId,addressId,quote.total()))throw new DomainException("订单尚未获得本次工作流的确认授权");return new AuthorizedOrder(quote,addressId);}
        if(!address.isDefaultAddress())throw new DomainException("自动授权必须使用当前默认地址");
        BigDecimal maximum; AutomationGrant grant=policies.lockedGrant(userId,session.getSessionId());
        if(grant!=null){if(!grant.isActiveAt(Instant.now())||!grant.getAddressId().equals(addressId)||!session.canUseAuthorizedCart(workflowId,cartId,addressId,quote.total())||!grant.canUse(workflowId,cartId))throw new DomainException("单次自动授权不存在或已失效");maximum=grant.getMaxAmount();}
        else {AutomationPolicy policy=policies.policy(userId);if(policy==null||!policy.isEnabled()||!policy.getDefaultAddressId().equals(addressId))throw new DomainException("自动授权不存在或已失效");maximum=policy.getMaxAmount();}
        if(quote.total().compareTo(maximum)>0)throw new DomainException("金额超过授权上限"); return new AuthorizedOrder(quote,addressId);
    }
    @Transactional public void authorizePayment(Long userId,AgentSession session,Long orderId,String workflowId,BigDecimal total){
        if(session.getMode()!=AgentMode.THIS_TASK_AUTO||!session.ownsCreatedOrder(orderId,workflowId))throw new DomainException("订单不属于当前会话工作流");
        AutomationGrant grant=policies.lockedGrant(userId,session.getSessionId());
        if(grant!=null){if(!grant.isActiveAt(Instant.now())||!grant.canPay(workflowId,orderId))throw new DomainException("单次自动授权不存在或已失效");if(total.compareTo(grant.getMaxAmount())>0)throw new DomainException("金额超过授权上限");return;}
        AutomationPolicy policy=policies.policy(userId);if(policy==null||!policy.isEnabled()||total.compareTo(policy.getMaxAmount())>0)throw new DomainException("自动授权不存在或已失效");
    }
}
