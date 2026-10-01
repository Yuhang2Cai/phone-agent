// 团购购买模块：根据后端套餐事实生成金额报价和确认凭证。
package com.fandazi.feature.purchase;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantEntity;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.DealPackageRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 购买报价服务：金额、库存、套餐版本均来自数据库，不接受客户端金额。 */
@Service
public class PurchaseQuoteService {
    private final PurchaseQuoteRepository quotes;
    private final DealPackageRepository deals;
    private final UserRepository users;
    private final ConfirmationGrantService confirmations;

    /** 注入报价、套餐、用户和确认凭证依赖。 */
    public PurchaseQuoteService(PurchaseQuoteRepository quotes, DealPackageRepository deals, UserRepository users, ConfirmationGrantService confirmations) {
        this.quotes = quotes; this.deals = deals; this.users = users; this.confirmations = confirmations;
    }

    /** 生成五分钟有效的购买报价，并返回下一步创建订单所需确认凭证。 */
    @Transactional
    public Map<String, Object> create(Long userId, String sessionId, String dealPublicId, int quantity) {
        return createQuote(userId, sessionId, dealPublicId, quantity, false);
    }

    /** 内部 V1 报价把凭证绑定具体 quoteId，防止相同套餐的其他报价替换用户确认的数量和金额。 */
    @Transactional
    public Map<String, Object> createForAgent(Long userId, String sessionId, String dealPublicId, int quantity) {
        return createQuote(userId, sessionId, dealPublicId, quantity, true);
    }

    /** 统一报价计算；页面历史调用保留原资源绑定，V1 调用使用报价身份和独立确认元数据。 */
    private Map<String, Object> createQuote(Long userId, String sessionId, String dealPublicId, int quantity, boolean agent) {
        // 输入校验：真实有效日期、上架状态、库存和限购共同决定是否可以准备购买。
        if (quantity <= 0) throw new DomainException("购买数量必须大于零");
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        DealPackageEntity deal = deals.findByPublicId(dealPublicId).orElseThrow(() -> new DomainException("套餐不存在"));
        if (!deal.isActive() || deal.getInventory() < quantity) throw new DomainException("套餐库存不足或已下架");
        if (quantity > deal.getPurchaseLimit()) throw new DomainException("超过套餐限购数量");
        if (java.time.LocalDate.now().isBefore(deal.getValidFrom()) || java.time.LocalDate.now().isAfter(deal.getValidTo()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "套餐不在可购买有效期内");
        int subtotal = Math.multiplyExact(deal.getPriceCents(), quantity);
        int discount = Math.max(0, deal.getOriginalPriceCents() * quantity - subtotal);
        int total = subtotal;
        Instant expiresAt = Instant.now().plusSeconds(300);
        String fingerprint = deal.getPublicId() + ":" + deal.getVersion() + ":" + quantity + ":" + total;
        var quote = quotes.save(new PurchaseQuote(UUID.randomUUID().toString(), user, deal, quantity, subtotal, discount, total,
                expiresAt, Instant.now(), "ACTIVE", fingerprint));
        // 结果构造：报价快照与凭证同事务持久化，准备阶段不创建订单或改变支付状态。
        ConfirmationGrantEntity grant = confirmations.issue(userId, sessionId, agent ? quote.getPublicId() : deal.getPublicId(), total, "deal-v" + deal.getVersion());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteId", quote.getPublicId()); data.put("dealId", deal.getPublicId()); data.put("quantity", quantity);
        data.put("subtotalCents", subtotal); data.put("discountCents", discount); data.put("totalCents", total);
        data.put("expiresAt", expiresAt.toString()); data.put("confirmationGrant", grant.getGrantId());
        data.put("dealVersion", deal.getVersion());
        if (agent) {
            data.put("confirmationExpiresAt", grant.getExpiresAt().toString());
            data.put("confirmationRuleVersion", grant.getRuleVersion());
        }
        return data;
    }

    /** 恢复本人有效报价；创建端必须使用这里的数量、金额、版本快照而不是工具载荷的猜测值。 */
    @Transactional(readOnly = true)
    public PurchaseQuote requireForCreation(Long userId, String quoteId) {
        var quote = quotes.findByPublicId(quoteId).orElseThrow(() -> new com.fandazi.feature.agent.ToolBoundaryException("NOT_FOUND", "购买报价不存在"));
        if (!quote.getUser().getId().equals(userId)) throw new com.fandazi.feature.agent.ToolBoundaryException("FORBIDDEN", "购买报价不属于当前用户");
        if (!quote.getExpiresAt().isAfter(Instant.now())) throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "购买报价已过期");
        if (!"ACTIVE".equals(quote.getStatus())) throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "购买报价已使用");
        return quote;
    }
}
