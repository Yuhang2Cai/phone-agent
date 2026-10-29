// V1 工具桥接模块：把执行器白名单动作交给真实团购领域，统一保护身份、确认、幂等与出口结构。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.catalog.*;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.support.*;
import com.fandazi.feature.voucher.VoucherService;
import com.fandazi.feature.coupon.CouponService;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次调用持有会话行锁；成功结果、凭证消费、业务事实与动作记录原子提交，失败全部回滚。 */
@Service
public class AgentV1ToolBridge {
    private final AgentSessionRepository sessions;
    private final AgentToolExecutionRepository executions;
    private final AgentToolSchemaValidator schema;
    private final ObjectMapper mapper;
    private final StoreService stores;
    private final DealPackageService deals;
    private final StoreReviewRepository reviews;
    private final GroupBuyOrderService groupOrders;
    private final PurchaseQuoteService quotes;
    private final VoucherService vouchers;
    private final RefundService refunds;
    private final ComplaintService complaints;
    private final CouponService coupons;
    private final FaqService faqs;
    private final ConfirmationGrantService grants;

    /** 仅注入真实领域服务；旧外卖 AgentToolService 不参与新路径。 */
    AgentV1ToolBridge(AgentSessionRepository sessions, AgentToolExecutionRepository executions, AgentToolSchemaValidator schema,
            ObjectMapper mapper, StoreService stores, DealPackageService deals, StoreReviewRepository reviews,
            GroupBuyOrderService groupOrders, PurchaseQuoteService quotes, VoucherService vouchers, RefundService refunds,
            ComplaintService complaints, CouponService coupons, FaqService faqs, ConfirmationGrantService grants) {
        this.sessions = sessions; this.executions = executions; this.schema = schema; this.mapper = mapper; this.stores = stores;
        this.deals = deals; this.reviews = reviews; this.groupOrders = groupOrders; this.quotes = quotes; this.vouchers = vouchers;
        this.refunds = refunds; this.complaints = complaints; this.coupons = coupons; this.faqs = faqs; this.grants = grants;
    }
    /** 按当前活动 run/trace 执行动作；已提交的相同指纹重放结果，不再要求重新消费一次性凭证。 */
    @Transactional
    public Map<String, Object> execute(String tool, InternalV1ToolRequest request) {
        // 输入校验：执行器键不能由 payload 决定；长度约束保护数据库字段而不截断身份。
        String expectedKey = request.runId() + ":" + request.taskId() + ":" + request.version();
        Long userId;
        try { userId = Long.valueOf(request.userId()); }
        catch (NumberFormatException exception) { throw new ToolBoundaryException("FORBIDDEN", "调用身份不可用"); }
        var session = sessions.findBySessionIdAndUserIdForUpdate(request.sessionId(), userId)
                .orElseThrow(() -> new ToolBoundaryException("FORBIDDEN", "会话不属于当前用户"));
        // 2.2任务只能从已核验TaskFence的新桥进入；旧run/trace正确也不能降级绕过代次。
        if("2.2".equals(session.getContextProtocolVersion()))throw new ToolBoundaryException("FORBIDDEN","任务协议不能使用无栅栏旧工具入口");
        if (!request.runId().equals(session.getActiveRunId()) || !request.traceId().equals(session.getTraceId())
                || !"running".equals(session.getActiveRunStatus())) throw new ToolBoundaryException("FORBIDDEN", "任务或本轮链路已失效");
        // 先拒绝失效任务，再核对键组成，避免身份伪造被误报为普通输入错误。
        if (!expectedKey.equals(request.idempotencyKey()) || expectedKey.length() > 255)
            throw new ToolBoundaryException("VALIDATION_ERROR", "执行器动作键不匹配");
        // 数据规范化：指纹递归排序 JSON 键，忽略每次输入会变化的 trace；载荷、工具和会话变化仍必须冲突。
        String fingerprint = fingerprint(tool, request);
        var existing = executions.findById(request.idempotencyKey());
        if (existing.isPresent()) {
            if (!fingerprint.equals(existing.get().getFingerprint())) throw new ToolBoundaryException("CONFLICT", "动作键对应不同请求");
            return replayKnowledge(tool, request, existing.get().getResult());
        }
        // 规则判断：准备只签发凭证且有速率限制；业务写必须获得当前 trace 的明确确认，每轮最多消费一次。
        if (schema.isPreparation(tool) && executions.countBySessionIdAndToolNameInAndCreatedAtAfter(request.sessionId(),
                List.of("create_purchase_quote", "prepare_refund", "prepare_complaint"), Instant.now().minusSeconds(60)) >= 30)
            throw new ToolBoundaryException("RATE_LIMITED", "确认准备过于频繁，请稍后重试");
        if (schema.isWrite(tool) && (session.getMode() != AgentMode.CONFIRM || !session.isConfirmed()))
            throw new ToolBoundaryException("FORBIDDEN", "该业务动作需要本轮明确确认");
        if (schema.isWrite(tool)) validatePending(tool, userId, request, session);
        Map<String, Object> result = dispatch(tool, userId, request);
        result = AgentDomainProjection.times(AgentContractCompatibility.toolData(result));
        schema.validateData(tool, result);
        // 结果构造：先验证真实输出再保存动作记录；任何异常都回滚已消费凭证和领域写入。
        if (schema.isWrite(tool)) session.consumeConfirmation();
        executions.saveAndFlush(new AgentToolExecution(request, userId, tool, fingerprint, result));
        return result;
    }
    /** 两代工具桥共用知识回放边界；其它已提交业务动作仍直接返回原事实，不重新执行。 */
    Map<String,Object> replayKnowledge(String tool, InternalV1ToolRequest request, Map<String,Object> result) {
        if (!"query_faq".equals(tool)) return result;
        try {
            return faqs.replayForAgent(new KnowledgeCatalog.Scope(request.idempotencyKey(), request.userId(), request.sessionId(), request.runId(), request.taskId()), result);
        } catch (IllegalStateException failure) {
            // 仅返回安全错误，不把来源正文、内部SQL或授权详情放进公开工具错误。
            throw new ToolBoundaryException("DEPENDENCY_UNAVAILABLE", "知识来源已失效，请重新提出问题");
        }
    }
    /** 具体动作与用户看过的摘要绑定；持有其他准备凭证不能借一次泛化确认替换动作、资源或正文。 */
    void validatePending(String tool, Long userId, InternalV1ToolRequest request, AgentSession session) {
        var pending = session.getPendingConfirmation(); var input = request.payload();
        String resource = switch (tool) { case "create_group_buy_order" -> text(input, "quoteId"); case "pay_mock_order" -> text(input, "orderId");
            case "request_refund" -> text(input, "voucherId"); default -> input.containsKey("orderId") ? text(input, "orderId") : "general"; };
        if (pending.isEmpty()) throw new ToolBoundaryException("FORBIDDEN", "请先查看该动作的确认摘要");
        var grant = grants.owned(userId, request.sessionId(), text(input, "confirmationGrantId"));
        if (!tool.equals(pending.get("action")) || !resource.equals(pending.get("resourceId"))
                || !grant.getAction().equals(tool.toUpperCase(Locale.ROOT)) || !grant.getResourceId().equals(resource)
                || !grant.getRuleVersion().equals(pending.get("ruleVersion"))
                || !(pending.get("amountCents") instanceof Number amount) || amount.longValue() != grant.getAmountCents())
            throw new ToolBoundaryException("CONFLICT", "动作不匹配已展示的确认摘要");
        // 正文也是明确确认的一部分；只规范首尾空白，不能替换投诉内容或退款原因。
        for (String field : List.of("reason", "subject", "description")) {
            if (input.containsKey(field) && (!(pending.get(field) instanceof String expected) || !expected.trim().equals(text(input, field).trim())))
                throw new ToolBoundaryException("CONFLICT", "提交内容与确认摘要不一致");
        }
    }
    /** 17 个工具的唯一实际分发；成功数据遵循契约包装，写工具返回直接领域摘要。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    Map<String, Object> dispatch(String tool, Long userId, InternalV1ToolRequest request) {
        Map<String, Object> input = request.payload();
        return switch (tool) {
            case "search_stores" -> Map.of("stores", stores.search(text(input, "cityCode"), text(input, "sort"), decimal(input, "latitude"),
                    decimal(input, "longitude"), text(input, "category"), integer(input, "minPriceCents"), integer(input, "maxPriceCents"))
                    .stream().map(AgentDomainProjection::store).toList());
            case "get_store_detail" -> Map.of("store", AgentDomainProjection.store(stores.require(text(input, "storeId"))));
            case "list_deal_packages" -> Map.of("deals", sortedDeals(input));
            case "get_deal_detail" -> Map.of("deal", AgentDomainProjection.deal(deals.require(text(input, "dealId"))));
            case "search_reviews" -> Map.of("reviews", searchReviews(input));
            case "list_user_orders" -> Map.of("orders", page(groupOrders.list(userId).stream()
                    .filter(value -> !input.containsKey("status") || input.get("status").equals(value.get("status"))).toList(), input));
            case "get_order_detail" -> Map.of("order", groupOrders.detail(userId, text(input, "orderId")));
            case "list_user_vouchers" -> Map.of("vouchers", vouchers.list(userId).stream().filter(value ->
                    (!input.containsKey("status") || input.get("status").equals(value.get("status")))
                    && (!input.containsKey("orderId") || input.get("orderId").equals(value.get("orderId")))).toList());
            case "create_purchase_quote" -> {
                // 当前购买领域尚未支持优惠券抵扣，不能静默忽略该参数并声称已应用优惠。
                if (input.containsKey("couponId")) throw new ToolBoundaryException("VALIDATION_ERROR", "当前报价尚不支持优惠券抵扣");
                yield quotes.createForAgent(userId, request.sessionId(), text(input, "dealId"), integer(input, "quantity"));
            }
            case "prepare_refund" -> preparation(userId, request.sessionId(), refunds.prepare(userId, request.sessionId(), text(input, "voucherId")));
            case "prepare_complaint" -> preparation(userId, request.sessionId(), complaints.prepare(userId, request.sessionId(), text(input, "orderId")));
            case "create_group_buy_order" -> {
                var quote = quotes.requireForCreation(userId, text(input, "quoteId"));
                var order = new LinkedHashMap<>(groupOrders.createFromQuote(userId, request.sessionId(), quote, text(input, "confirmationGrantId"), request.idempotencyKey()));
                // 新订单支付授权独立签发；这里只返回内部凭证，绝不推进支付状态或复用购买确认。
                // 秒杀授权截止沿用订单数据库支付截止；签发稍晚不能重新获得五分钟预留。
                var payment = order.containsKey("paymentExpiresAt")
                        ? grants.issueForActionUntil(userId,request.sessionId(),"PAY_MOCK_ORDER",(String)order.get("orderId"),(Integer)order.get("totalCents"),"pay-v1",Instant.parse((String)order.get("paymentExpiresAt")))
                        : grants.issueForAction(userId, request.sessionId(), "PAY_MOCK_ORDER", (String) order.get("orderId"), (Integer) order.get("totalCents"), "pay-v1");
                order.put("paymentConfirmationGrantId", payment.getGrantId()); order.put("paymentConfirmationExpiresAt", payment.getExpiresAt().toString());
                order.put("ruleVersion", "pay-v1"); yield order;
            }
            case "pay_mock_order" -> groupOrders.payConfirmed(userId, request.sessionId(), text(input, "orderId"), text(input, "confirmationGrantId"));
            case "request_refund" -> refunds.request(userId, request.sessionId(), text(input, "voucherId"), text(input, "reason"), text(input, "confirmationGrantId"));
            case "query_coupon" -> {
                // 先核对引用资源，但不根据模型引用捏造券适用范围；现有领域返回真实已领取券规则。
                if (input.containsKey("storeId")) stores.require(text(input, "storeId"));
                if (input.containsKey("dealId")) deals.require(text(input, "dealId"));
                yield Map.of("coupons", coupons.list(userId).stream().map(AgentDomainProjection::coupon).toList());
            }
            // 模型只提供问题；来源绑定身份全部来自已核验工具信封，V1和Task桥共享此唯一分发。
            case "query_faq" -> Map.of("faqs", faqs.queryForAgent(text(input, "question"), text(input, "topic"),
                    new KnowledgeCatalog.Scope(request.idempotencyKey(), request.userId(), request.sessionId(), request.runId(), request.taskId())));
            case "submit_complaint" -> complaints.submit(userId, request.sessionId(), text(input, "orderId"), text(input, "subject"), text(input, "description"), text(input, "confirmationGrantId"));
            default -> throw new ToolBoundaryException("NOT_FOUND", "工具不存在");
        };
    }
    /** 准备结果补充真实凭证期限和规则；refund.expiresAt 仍是券有效期，不能拿它显示确认截止。 */
    private Map<String, Object> preparation(Long userId, String sessionId, Map<String, Object> data) {
        var result = AgentContractCompatibility.toolData(data);
        var grant = grants.owned(userId, sessionId, (String) result.get("confirmationGrantId"));
        result.put("confirmationExpiresAt", grant.getExpiresAt().toString()); result.put("confirmationRuleVersion", grant.getRuleVersion());
        return result;
    }
    /** 当前套餐服务缺少降序分支，桥接对真实集合执行确定性排序，不伪造结果。 */
    private List<Map<String, Object>> sortedDeals(Map<String, Object> input) {
        var values = new ArrayList<>(deals.list(text(input, "storeId"), text(input, "sort")));
        if ("PRICE_DESC".equals(input.get("sort"))) values.sort(Comparator.comparingInt(DealPackageEntity::getPriceCents).reversed().thenComparing(DealPackageEntity::getPublicId));
        return values.stream().map(AgentDomainProjection::deal).toList();
    }
    /** 评价查询按真实门店与可选订单套餐关系过滤；分页每页 20，缺省为第零页。 */
    private List<Map<String, Object>> searchReviews(Map<String, Object> input) {
        var store = stores.require(text(input, "storeId"));
        Set<Long> orderIds = null;
        if (input.containsKey("dealId")) {
            var deal = deals.require(text(input, "dealId"));
            if (!deal.getStore().getId().equals(store.getId())) throw new ToolBoundaryException("VALIDATION_ERROR", "套餐不属于指定门店");
            orderIds = new HashSet<>(entityManager.createQuery("select o.id from GroupBuyOrderEntity o where o.deal.id = :deal", Long.class)
                    .setParameter("deal", deal.getId()).getResultList());
        }
        Set<Long> selected = orderIds;
        var values = reviews.findAll().stream().filter(value -> value.getStore().getId().equals(store.getId()))
                .filter(value -> selected == null || selected.contains(value.getOrderId()))
                .filter(value -> !input.containsKey("rating") || value.getRating().compareTo(decimal(input, "rating")) == 0)
                .filter(value -> !input.containsKey("keyword") || value.getContent().contains(text(input, "keyword")))
                .sorted(Comparator.comparing(StoreReviewEntity::getCreatedAt).reversed().thenComparing(StoreReviewEntity::getId))
                .map(AgentDomainProjection::review).toList();
        return page(values, input);
    }
    /** 分页使用长整数计算，避免极大 page 溢出后回到第一页或抛出内部异常。 */
    private List<Map<String, Object>> page(List<Map<String, Object>> values, Map<String, Object> input) {
        long start = input.containsKey("page") ? Math.multiplyExact(new BigDecimal(input.get("page").toString()).longValueExact(), 20L) : 0;
        if (start >= values.size()) return List.of();
        return values.subList((int) start, (int) Math.min(start + 20, values.size()));
    }
    /** 指纹包括工具与可信身份，但不包括可变化的 trace；排序保证 JSON 键顺序不影响重试。 */
    private String fingerprint(String tool, InternalV1ToolRequest request) {
        try {
            Object value = canonical(Map.of("tool", tool, "user", request.userId(), "session", request.sessionId(), "run", request.runId(),
                    "task", request.taskId(), "version", request.version(), "payload", request.payload()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) { throw new ToolBoundaryException("INTERNAL_ERROR", "无法核对执行器动作"); }
    }
    /** 递归排序对象键并规范数字，不改变数组次序或用户正文。 */
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) { var result = new TreeMap<String, Object>(); source.forEach((key, item) -> result.put(String.valueOf(key), canonical(item))); return result; }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
    /** schema 已限制字符串类型，此处只读取可选值，不将对象或 null 拼成字符串。 */
    private String text(Map<String, Object> input, String key) { return (String) input.get(key); }
    /** 数字精确转换；超出领域整数范围时由控制器返回验证失败，不截断金额或数量。 */
    private Integer integer(Map<String, Object> input, String key) { return input.containsKey(key) ? new BigDecimal(input.get(key).toString()).intValueExact() : null; }
    /** 经纬度与评分保留原数值精度，缺失可选条件保持 null。 */
    private BigDecimal decimal(Map<String, Object> input, String key) { return input.containsKey(key) ? new BigDecimal(input.get(key).toString()) : null; }
    /** 评价按套餐过滤只读取订单关系，不暴露其他用户订单资料。 */
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
}
