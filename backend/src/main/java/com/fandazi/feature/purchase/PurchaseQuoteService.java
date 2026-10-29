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
    private final com.fandazi.feature.seckill.SeckillQuotePolicy seckill;

    /** 注入报价、套餐、用户和确认凭证依赖。 */
    public PurchaseQuoteService(PurchaseQuoteRepository quotes, DealPackageRepository deals, UserRepository users, ConfirmationGrantService confirmations,
                                com.fandazi.feature.seckill.SeckillQuotePolicy seckill) {
        this.quotes = quotes; this.deals = deals; this.users = users; this.confirmations = confirmations;
        this.seckill=seckill;
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

    /** 页面报价绑定独立报价编号，并补齐数量边界及展示字段；准备阶段不创建订单。 */
    @Transactional
    public Map<String, Object> createForPage(Long userId, String sessionId, String dealPublicId, int quantity) {
        var result = createQuote(userId, sessionId, dealPublicId, quantity, true);
        var deal = deals.findByPublicId(dealPublicId).orElseThrow(() -> new DomainException("套餐不存在"));
        // 展示数据与确认使用同一后端资源，端上不从导航参数推断价格、资格或数量范围。
        result.put("dealTitle", deal.getTitle()); result.put("storeName", deal.getStore().getName());
        result.put("imageUrl", deal.getImageUrl()); result.put("imageAlt", deal.getImageAlt());
        result.put("usageRules", deal.getUsageRules() == null ? "有效期：" + deal.getValidFrom() + " 至 " + deal.getValidTo() : deal.getUsageRules());
        result.put("minQuantity", 1); result.put("maxQuantity", Math.min(deal.getInventory(),
                result.get("remainingQuota") instanceof Integer remaining?remaining:deal.getPurchaseLimit()));
        return result;
    }

    /** 统一报价计算；页面历史调用保留原资源绑定，V1 调用使用报价身份和独立确认元数据。 */
    private Map<String, Object> createQuote(Long userId, String sessionId, String dealPublicId, int quantity, boolean agent) {
        // 输入校验：真实有效日期、上架状态、库存和限购共同决定是否可以准备购买。
        if (quantity <= 0) throw new DomainException("购买数量必须大于零");
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        DealPackageEntity deal = deals.findByPublicId(dealPublicId).orElseThrow(() -> new DomainException("套餐不存在"));
        var activity=seckill.activityFor(deal);
        Instant now=activity==null ? Instant.now() : seckill.now();
        int remainingQuota=activity==null?deal.getPurchaseLimit():seckill.remainingQuota(activity,userId);
        if(activity!=null) {
            // 秒杀由活动窗口决定购买资格，允许今天买明天使用的券；所有旧报价入口共享同一门禁。
            seckill.requireOpen(activity,now,quantity);
            if(quantity>remainingQuota)throw new com.fandazi.feature.seckill.SeckillRejectedException("LIMIT_REACHED","已达到活动累计限购数量");
        } else {
            // 普通团购保留原有有效期与单次数量规则，不能被秒杀时间语义追溯改变。
            if (!deal.isActive() || deal.getInventory() < quantity) throw new DomainException("套餐库存不足或已下架");
            if (quantity > deal.getPurchaseLimit()) throw new DomainException("超过套餐限购数量");
            if (java.time.LocalDate.now().isBefore(deal.getValidFrom()) || java.time.LocalDate.now().isAfter(deal.getValidTo()))
                throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "套餐不在可购买有效期内");
        }
        // 金额规范化：商品总价采用原价与售价中较高者，折扣只计算一次，确保总价减折扣等于实付。
        int total,subtotal;
        try{
            total=Math.multiplyExact(deal.getPriceCents(),quantity);
            subtotal=Math.multiplyExact(Math.max(deal.getOriginalPriceCents(),deal.getPriceCents()),quantity);
        }catch(ArithmeticException overflow){
            // 单价合法不代表整单仍能用整数分表达；明确拒绝，不能截断、转浮点或返回不明500。
            if(activity!=null)throw new com.fandazi.feature.seckill.SeckillRejectedException("AMOUNT_EXCEEDED","购买总额超出金额上限，请减少数量");
            throw overflow;
        }
        int discount = Math.subtractExact(subtotal, total);
        Instant expiresAt = now.plusSeconds(300);
        if(activity!=null && activity.getEndsAt().isBefore(expiresAt)) expiresAt=activity.getEndsAt();
        String fingerprint = com.fandazi.feature.seckill.SeckillQuotePolicy.fingerprint(deal,activity,quantity,total);
        var quote = quotes.save(new PurchaseQuote(UUID.randomUUID().toString(), user, deal, quantity, subtotal, discount, total,
                expiresAt, now, "ACTIVE", fingerprint));
        if(activity!=null) quote.bindSeckill(activity);
        // 结果构造：报价快照与凭证同事务持久化，准备阶段不创建订单或改变支付状态。
        // 秒杀旧报价也绑定完整quote，旧建单入口可转入统一快照路径，不能拿套餐级授权绕过活动版本。
        // 秒杀确认与数据库计算的报价截止一致；活动提前结束不能留下更长的确认有效期。
        ConfirmationGrantEntity grant = activity==null
                ? confirmations.issue(userId,sessionId,agent?quote.getPublicId():deal.getPublicId(),total,"deal-v"+deal.getVersion())
                : confirmations.issueForActionUntil(userId,sessionId,"CREATE_GROUP_BUY_ORDER",quote.getPublicId(),total,"deal-v"+deal.getVersion(),expiresAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteId", quote.getPublicId()); data.put("dealId", deal.getPublicId()); data.put("quantity", quantity);
        data.put("subtotalCents", subtotal); data.put("discountCents", discount); data.put("totalCents", total);
        data.put("expiresAt", expiresAt.toString()); data.put("confirmationGrant", grant.getGrantId());
        data.put("dealVersion", deal.getVersion());
        // 公开活动快照与服务器时间来自同一次规则计算；端上据此展示，不能将报价当作锁库存成功。
        if(activity!=null) {
            data.put("activityId",activity.getPublicId());data.put("activityVersion",activity.getActivityVersion());
            data.put("serverTime",now.toString());
            data.put("remainingQuota",remainingQuota);
        }
        if (agent) {
            data.put("confirmationExpiresAt", grant.getExpiresAt().toString());
            data.put("confirmationRuleVersion", grant.getRuleVersion());
        }
        return data;
    }

    /** 恢复本人有效报价；创建端必须使用这里的数量、金额、版本快照而不是工具载荷的猜测值。 */
    @Transactional(readOnly = true)
    public PurchaseQuote requireForCreation(Long userId, String quoteId) {
        var quote = owned(userId, quoteId);
        // 秒杀报价恢复与创建使用同一数据库权威时钟；主机时间差不能提前失效或延长购买窗口。
        Instant now=quote.getSeckillActivity()==null?Instant.now():seckill.now();
        if (!quote.getExpiresAt().isAfter(now)) throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "购买报价已过期");
        if (!"ACTIVE".equals(quote.getStatus())) throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "购买报价已使用");
        return quote;
    }

    /** 读取本人报价，包括已消费快照供安全幂等重放；有效期和消费状态在写事务内复核。 */
    @Transactional(readOnly = true)
    public PurchaseQuote owned(Long userId, String quoteId) {
        var quote = quotes.findByPublicId(quoteId).orElseThrow(() -> new com.fandazi.feature.agent.ToolBoundaryException("NOT_FOUND", "购买报价不存在"));
        if (!quote.getUser().getId().equals(userId)) throw new com.fandazi.feature.agent.ToolBoundaryException("FORBIDDEN", "购买报价不属于当前用户");
        // 初始化后续事务所需关联，避免关闭查询事务后读取懒加载代理失败。
        quote.getDeal().getPublicId();
        return quote;
    }
}
