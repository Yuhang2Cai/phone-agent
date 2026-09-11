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
    @Transactional(readOnly=true) public AuthorizedOrder authorize(Long userId,AgentSession session,Long cartId,Long addressId){
        if(!session.getUser().getId().equals(userId))throw new DomainException("会话不属于当前用户"); addresses.owned(userId,addressId);
        if(addressMetadata.findByAddressId(addressId).map(m->!m.isValid()).orElse(false))throw new DomainException("地址已失效");
        CartQuote quote=carts.validate(userId,cartId); if(session.getMode()==AgentMode.CONFIRM){if(!session.isConfirmed())throw new DomainException("订单尚未获得确认授权");return new AuthorizedOrder(quote,addressId);}
        BigDecimal maximum=null; AutomationGrant grant=policies.grant(userId,session.getSessionId());
        if(grant!=null&&grant.isActiveAt(Instant.now())&&grant.getAddressId().equals(addressId)) maximum=grant.getMaxAmount();
        if(maximum==null){AutomationPolicy policy=policies.policy(userId);if(policy==null||!policy.isEnabled()||!policy.getDefaultAddressId().equals(addressId))throw new DomainException("自动授权不存在或已失效");maximum=policy.getMaxAmount();}
        if(quote.total().compareTo(maximum)>0)throw new DomainException("金额超过授权上限"); return new AuthorizedOrder(quote,addressId);
    }
}
