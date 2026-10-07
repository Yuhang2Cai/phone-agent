// 公开卡片兼容模块：按 canonical 资源引用重新查询领域事实，集中生成旧 Android 所需字段。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.catalog.*;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import com.fandazi.feature.voucher.VoucherService;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.coupon.CouponService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 模型金额、状态和资格都不可信；门店和套餐公开查，订单、券、优惠券按当前用户查。 */
@Service
public class AgentCardEnrichmentService {
    private final StoreService stores;
    private final DealPackageService deals;
    private final GroupBuyOrderService orders;
    private final VoucherService vouchers;
    private final CouponService coupons;
    private final RefundService refunds;
    private final PurchaseQuoteService quotes;
    private final ConfirmationGrantService grants;
    private final StoreReviewRepository reviews;
    /** 仅复用已有真实浏览摘要补齐聊天门店展示，不改变工具参数或领域写入。 */
    private StoreBrowseService browse;

    /** 生产路径注入 Task4 既有浏览查询；历史纯领域构造器仍可使用原公开事实。 */
    @Autowired
    void attachBrowse(StoreBrowseService browse) { this.browse = browse; }

    /** 注入完整领域依赖，复杂卡片重新校验资源归属，不从模型复制业务事实。 */
    @Autowired
    public AgentCardEnrichmentService(StoreService stores, DealPackageService deals, GroupBuyOrderService orders,
            VoucherService vouchers, CouponService coupons, RefundService refunds, PurchaseQuoteService quotes,
            ConfirmationGrantService grants, StoreReviewRepository reviews) {
        this.stores = stores; this.deals = deals; this.orders = orders; this.vouchers = vouchers; this.coupons = coupons;
        this.refunds = refunds; this.quotes = quotes; this.grants = grants; this.reviews = reviews;
    }
    /** 保留历史公开目录构造兼容；该入口不能查询任何受用户归属保护的卡片。 */
    public AgentCardEnrichmentService(StoreService stores, DealPackageService deals) {
        this(stores, deals, null, null, null, null, null, null, null);
    }
    /** 历史公开目录入口也接受 canonical 引用；受保护卡片须使用带可信身份的重载。 */
    @Transactional(readOnly = true)
    public Map<String, Object> enrich(Map<String, Object> reference) { return enrich(null, null, reference); }

    /** 按类型重查事实；返回旧 Android 可识别的平铺卡片，绝不下发任何确认凭证。 */
    @Transactional(readOnly = true)
    public Map<String, Object> enrich(Long userId, String sessionId, Map<String, Object> reference) {
        // 即时事件已经适配的旧售后卡再次进入最终响应时，先恢复同一 canonical 资源引用，避免二次适配失败。
        if (!reference.containsKey("cardType") && Set.of("after_sale_voucher_choice", "after_sale_refund", "after_sale_complaint").contains(text(reference, "type"))) {
            var canonical = new LinkedHashMap<String, Object>(reference);
            boolean voucher = "after_sale_voucher_choice".equals(reference.get("type"));
            canonical.put("cardType", voucher ? "voucher" : "confirmation"); canonical.put("data", reference);
            canonical.put("actions", voucher ? List.of(Map.of("actionType", "select_voucher")) : List.of());
            return enrich(userId, sessionId, canonical);
        }
        String type = text(reference, reference.containsKey("cardType") ? "cardType" : "type");
        // 先校验类型白名单再读取资源，确保未知类型稳定拒绝且不会触发领域查询或模拟兜底。
        if (!Set.of("store", "deal", "order", "voucher", "coupon", "review", "confirmation", "error").contains(type)) {
            throw new DomainException("VALIDATION_ERROR:不支持的 Agent 卡片类型");
        }
        String resource = reference.containsKey("cardType") ? required(reference, "resourceId") : required(reference, switch (type) {
            case "store" -> "storePublicId"; case "deal" -> "dealPublicId"; case "order" -> "orderPublicId"; default -> "resourceId"; });
        Map<String, Object> facts;
        String legacyType = type;
        switch (type) {
            case "store" -> {
                facts = AgentDomainProjection.store(stores.require(resource));
                // 图片、评分、评价数及团购摘要来自同一 Spring 目录查询，缺图/缺评分仍保持空值。
                if (browse != null) {
                    var detail = browse.detail(resource, null, null);
                    facts.put("imageUrl", detail.imageUrl()); facts.put("imageAlt", detail.imageAlt());
                    facts.put("rating", detail.rating()); facts.put("reviewCount", detail.reviewCount());
                    if (detail.featuredDeal() != null) facts.put("dealSummary", detail.featuredDeal().title());
                }
            }
            case "deal" -> {
                var deal = deals.require(resource); facts = AgentDomainProjection.deal(deal);
                facts.put("storeName", deal.getStore().getName());
            }
            case "order" -> {
                requireUser(userId); facts = orders.detail(userId, resource);
                // 卡片有效期取本人订单真实券集合的最早截止；没有券事实时保持缺值，不读设备时间推测。
                if (facts.get("vouchers") instanceof List<?> orderVouchers) {
                    orderVouchers.stream().filter(value -> value instanceof Map<?, ?>).map(value -> ((Map<?, ?>) value).get("expiresAt"))
                        .filter(Objects::nonNull).map(String::valueOf).min(String::compareTo).ifPresent(value -> facts.put("expiresAt", value));
                }
            }
            case "voucher" -> {
                requireUser(userId); facts = vouchers.detail(userId, resource); facts.put("voucherId", facts.get("publicId"));
                // 动作只决定界面用途，资源与资格仍来自本人的真实券。
                if (hasAction(reference, "select_voucher")) legacyType = "after_sale_voucher_choice";
            }
            case "coupon" -> {
                requireUser(userId);
                facts = new LinkedHashMap<>(coupons.list(userId).stream().filter(value -> resource.equals(value.get("claimId"))
                        || resource.equals(value.get("couponId"))).findFirst().orElseThrow(() -> new DomainException("优惠券不存在")));
                facts.put("title", facts.get("name"));
            }
            case "review" -> {
                var input = object(reference.get("data")); var store = stores.require(resource);
                // 契约没有单条评价公开编号，通过门店、作者和时间定位真实记录，内容和评分重新读取。
                var review = reviews.findAll().stream().filter(value -> value.getStore().getId().equals(store.getId())
                        && value.getReviewerName().equals(input.get("reviewerName")) && value.getCreatedAt().toString().equals(input.get("createdAt")))
                        .findFirst().orElseThrow(() -> new DomainException("评价不存在"));
                facts = new LinkedHashMap<>(AgentDomainProjection.review(review)); facts.put("title", review.getContent());
            }
            case "confirmation" -> {
                requireUser(userId); facts = enrichSummary(userId, sessionId, object(reference.get("data")));
                legacyType = switch (text(facts, "action")) { case "request_refund" -> "after_sale_refund";
                    case "submit_complaint" -> "after_sale_complaint"; default -> "confirmation"; };
            }
            case "error" -> facts = new LinkedHashMap<>(Map.of("title", "本轮操作暂未完成，请重试"));
            default -> throw new DomainException("VALIDATION_ERROR:不支持的 Agent 卡片类型");
        }
        // 结果构造：兼容别名只在此出口出现，内部领域保持公开 ID 和整数分；任意私有字段递归剔除。
        var card = AgentDomainProjection.times(facts); card.put("type", legacyType); card.put("resourceId", resource);
        if ("store".equals(type)) card.put("storePublicId", resource);
        if ("deal".equals(type)) { card.put("dealPublicId", resource); card.put("storePublicId", facts.get("storeId")); }
        if ("order".equals(type)) {
            card.put("orderPublicId", resource); card.put("title", facts.get("dealTitle")); card.put("dealName", facts.get("dealTitle"));
            card.put("amount", new BigDecimal(facts.get("totalCents").toString()).movePointLeft(2).setScale(2).toPlainString());
        }
        return AgentContractCompatibility.publicEventPayload(card);
    }

    /** 公开确认摘要的金额、名称、规则和截止来自 Spring；正文是用户待确认输入，不能替代业务资格。 */
    @Transactional(readOnly = true)
    public Map<String, Object> enrichSummary(Long userId, String sessionId, Map<String, Object> supplied) {
        if (!supplied.containsKey("action")) return supplied;
        requireUser(userId);
        var summary = new LinkedHashMap<>(supplied);
        String action = required(supplied, "action"); String resource = required(supplied, "resourceId");
        int amount; String rule;
        switch (action) {
            case "create_group_buy_order" -> {
                var quote = quotes.requireForCreation(userId, resource); var deal = quote.getDeal();
                amount = quote.getTotalCents(); rule = "deal-v" + deal.getVersion();
                summary.put("quantity", quote.getQuantity()); summary.put("dealTitle", deal.getTitle()); summary.put("storeName", deal.getStore().getName());
                summary.put("title", "确认创建团购订单");
            }
            case "pay_mock_order" -> {
                var order = orders.detail(userId, resource);
                if (!"PENDING_PAYMENT".equals(order.get("status"))) throw new DomainException("订单当前不可支付");
                amount = (Integer) order.get("totalCents"); rule = "pay-v1";
                summary.put("orderId", resource); summary.put("title", "确认模拟支付");
            }
            case "request_refund" -> {
                var eligibility = refunds.eligibility(userId, resource);
                amount = (Integer) eligibility.get("amountCents"); rule = "refund-v1";
                summary.put("voucherId", resource); summary.put("orderId", eligibility.get("orderId"));
                summary.put("dealTitle", eligibility.get("dealTitle")); summary.put("title", "确认提交退款申请");
            }
            case "submit_complaint" -> {
                if (!"general".equals(resource)) orders.detail(userId, resource);
                amount = 0; rule = "complaint-v1"; summary.put("orderId", "general".equals(resource) ? "" : resource);
                summary.put("title", "确认提交投诉");
            }
            default -> throw new DomainException("VALIDATION_ERROR:不支持的确认动作");
        }
        // 没有本会话已签发的有效准备就不能展示确认；截止只能来自匹配真实金额和版本的凭证。
        var grant = grants.pending(userId, sessionId, action.toUpperCase(Locale.ROOT), resource, amount, rule);
        summary.put("amountCents", amount); summary.put("ruleVersion", rule); summary.put("expiresAt", grant.getExpiresAt().toString());
        return AgentContractCompatibility.publicEventPayload(summary);
    }
    /** 受保护卡片必须带 Spring 用户身份，不能从卡片 payload 获取身份。 */
    private void requireUser(Long userId) { if (userId == null) throw new DomainException("FORBIDDEN:卡片需要可信用户上下文"); }
    /** 只判断选择用途，不信任模型 enabled 或其他资源的授权。 */
    private boolean hasAction(Map<String, Object> reference, String type) {
        return reference.get("actions") instanceof List<?> actions && actions.stream().anyMatch(value -> value instanceof Map<?, ?> action && type.equals(action.get("actionType")));
    }
    /** 引用必须是非空字符串，不把任意对象转成路径。 */
    private String required(Map<String, Object> values, String key) {
        String value = text(values, key); if (value.isBlank()) throw new DomainException("VALIDATION_ERROR:卡片缺少资源或动作"); return value;
    }
    /** 保持非法类型为空供上层拒绝，不引入有损转换。 */
    private String text(Map<String, Object> values, String key) { return values.get(key) instanceof String value ? value : ""; }
    /** 只接受 JSON 对象作为摘要，未知结构不参与事实投影。 */
    private Map<String, Object> object(Object value) {
        var result = new LinkedHashMap<String, Object>(); if (value instanceof Map<?, ?> source) source.forEach((key, field) -> result.put(String.valueOf(key), field)); return result;
    }
}
