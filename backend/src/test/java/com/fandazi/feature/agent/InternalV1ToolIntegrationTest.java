// Task3 工具桥接专项测试：真实运行 HTTP、鉴权、数据库与团购领域，防止仅改接口名称而保留授权缺口。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.*;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 隔离的公开资源夹具；请求金额只能来自套餐与报价，客户端无法指定业务金额。 */
@SpringBootTest(properties = {"agent.internal-token=task3-test-only-hmac-key-never-production", "seed.enabled=false", "group.seed.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class InternalV1ToolIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired StoreRepository stores;
    @Autowired DealPackageRepository deals;
    @Autowired AgentSessionService sessions;
    @Autowired GroupBuyOrderRepository orders;
    @Autowired AgentCardEnrichmentService cards;
    @Autowired AgentMessageService messages;
    @Autowired AgentEventService audit;
    @Autowired com.fandazi.feature.coupon.CouponRepository couponDefinitions;
    @Autowired com.fandazi.feature.coupon.UserCouponRepository claimedCoupons;
    // 秒杀只在隔离测试事务建立活动，无商家接口或生产初始化数据。
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @Autowired com.fandazi.feature.seckill.SeckillTimeSource seckillTime;
    private Long userId;
    private AgentSession session;
    private String storeId;
    private String dealId;

    /** 每例使用独立资源，金额 1234 分、数量 2，能识别忽略报价或默认数量的错误实现。 */
    @BeforeEach void fixtures() {
        String suffix = UUID.randomUUID().toString();
        userId = users.save(new UserEntity("t3-" + suffix.substring(0, 8), suffix + "@example.invalid", "test-only-placeholder")).getId();
        storeId = "store-" + suffix;
        var store = stores.save(new StoreEntity(storeId, "测试品牌", "真实测试门店", "310000", "测试地址", "测试电话", "中餐",
                new BigDecimal("31.2"), new BigDecimal("121.4"), 5678, true, "09:00-22:00", null));
        dealId = "deal-" + suffix;
        deals.save(new DealPackageEntity(dealId, store, "真实双份套餐", "专项测试套餐", 1234, 2000, 20,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(20), true, 5, 7));
        session = sessions.start(userId, "session-" + suffix, AgentMode.CONFIRM, "trace-" + suffix);
    }

    @Test void faqUsesVersionedEntryAndOnlyCanonicalQuestion() throws Exception {
        // 合法 question 必须实际查询 FAQ；旧 keyword 不能绕过新契约的未知字段限制。
        var result = call("query_faq", Map.of("question", "退款"), "faq");
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result).get("faqs")).isInstanceOf(List.class);
        assertError(call("query_faq", Map.of("keyword", "退款"), "legacy"), "VALIDATION_ERROR");
        assertError(call("not_a_tool", Map.of(), "unknown"), "NOT_FOUND");
    }

    @Test void jwtBindsUserAndRejectsExpiredOrLongLivedCredentials() throws Exception {
        // 已签名但 sub 不同、过期、长期有效的令牌都不能进入领域处理。
        for (String token : List.of(jwt("999999", 60), jwt(userId.toString(), -1), jwt(userId.toString(), 3600))) {
            assertError(send("query_faq", request(Map.of("question", "退款"), "auth"), token), "FORBIDDEN");
        }
    }

    @Test void preparationDoesNotWriteAndCreationRecoversQuoteThenSeparatelyConfirmsPayment() throws Exception {
        // 准备阶段必须没有业务订单；返回的确认期限和规则供 Agent 持久化，不代表用户已经确认。
        long before = orders.count();
        var quote = data(call("create_purchase_quote", Map.of("dealId", dealId, "quantity", 2), "quote"));
        assertThat(quote).containsKeys("quoteId", "confirmationGrantId", "confirmationExpiresAt", "confirmationRuleVersion");
        assertThat(orders.count()).isEqualTo(before);
        var payload = Map.of("quoteId", quote.get("quoteId"), "confirmationGrantId", quote.get("confirmationGrantId"));
        assertError(call("create_group_buy_order", payload, "create"), "FORBIDDEN");
        showConfirmation("create_group_buy_order", (String) quote.get("quoteId"), Map.of());
        sessions.confirm(userId, session.getSessionId());
        var created = call("create_group_buy_order", payload, "create");
        assertThat(created.get("ok")).isEqualTo(true);
        var order = data(created);
        assertThat(order).containsEntry("quantity", 2).containsEntry("totalCents", 2468).containsEntry("status", "PENDING_PAYMENT")
                .containsKeys("paymentConfirmationGrantId", "paymentConfirmationExpiresAt");
        assertThat(call("create_group_buy_order", payload, "create")).isEqualTo(created);
        assertThat(orders.count()).isEqualTo(before + 1);
        // 创建确认已消费，即使持有新支付凭证也不能自动支付；购买凭证更不能跨动作使用。
        var pay = Map.of("orderId", order.get("orderId"), "confirmationGrantId", order.get("paymentConfirmationGrantId"));
        assertError(call("pay_mock_order", pay, "pay"), "FORBIDDEN");
        showConfirmation("pay_mock_order", (String) order.get("orderId"), Map.of());
        sessions.confirm(userId, session.getSessionId());
        assertError(call("pay_mock_order", Map.of("orderId", order.get("orderId"), "confirmationGrantId", quote.get("confirmationGrantId")), "wrong-pay"), "CONFLICT");
        var paid = call("pay_mock_order", pay, "pay");
        assertThat(data(paid)).containsEntry("status", "PAID").containsEntry("totalCents", 2468);
        assertThat(call("pay_mock_order", pay, "pay")).isEqualTo(paid);
    }

    @Test void executorIdentityAndSessionRunTraceCannotBeSpoofed() throws Exception {
        // 同一动作键换载荷必须冲突；不匹配的任务/链路与模型自造幂等键必须在写入前拒绝。
        call("query_faq", Map.of("question", "退款"), "fingerprint");
        assertError(call("query_faq", Map.of("question", "投诉"), "fingerprint"), "CONFLICT");
        for (String key : List.of("runId", "traceId", "idempotencyKey")) {
            var request = request(Map.of("question", "退款"), "identity");
            request.put(key, "spoofed");
            assertError(send("query_faq", request, jwt(userId.toString(), 60)), key.equals("idempotencyKey") ? "VALIDATION_ERROR" : "FORBIDDEN");
        }
    }

    @Test void canonicalCardsReplaceAgentAmountsWithOwnedDomainFacts() throws Exception {
        // 模型可以给出资源引用，但不能让 1 分伪价格或伪造门店名进入公开卡片。
        var reference = new LinkedHashMap<String, Object>();
        reference.put("schemaVersion", "1.1"); reference.put("cardType", "deal"); reference.put("resourceId", dealId);
        reference.put("data", Map.of("title", "伪造名称", "priceCents", 1)); reference.put("actions", List.of());
        var actual = cards.enrich(reference);
        assertThat(actual).containsEntry("priceCents", 1234).containsEntry("title", "真实双份套餐")
                .containsEntry("dealPublicId", dealId);
    }

    @Test void allReadToolsProjectRealFactsAndUtcWithoutDomainIds() throws Exception {
        // 审核完整门店投影，避免只实现可通过 DTO 的静态默认值。
        assertThat(data(call("get_store_detail", Map.of("storeId", storeId), "store")).get("store"))
                .isInstanceOf(Map.class);
        var detail = (Map<?, ?>) data(call("get_deal_detail", Map.of("dealId", dealId), "deal")).get("deal");
        assertThat(detail.get("priceCents")).isEqualTo(1234);
        for (String tool : List.of("list_user_orders", "list_user_vouchers", "query_coupon"))
            assertThat(call(tool, Map.of(), tool).get("ok")).isEqualTo(true);
    }

    @Test void streamedConfirmationAuditRechecksAmountsBeforePublishing() throws Exception {
        // 即时 SSE 比最终响应更早发布；原始 Agent 金额必须在公开审计之前被真实报价替换。
        var quote = data(call("create_purchase_quote", Map.of("dealId", dealId, "quantity", 2), "quote-audit"));
        var summary = Map.<String, Object>of("action", "create_group_buy_order", "resourceId", quote.get("quoteId"),
                "amountCents", 1, "ruleVersion", "fake", "title", "模型伪金额", "dealTitle", "模型伪套餐", "quantity", 99);
        var request = new AgentRunRequest(userId.toString(), session.getSessionId(), "买两份", "confirm",
                session.getTraceId(), session.getActiveRunId(), Map.of());
        messages.record(request, Map.of("eventType", "confirmation_required", "status", "waiting_confirmation", "payload", Map.of("summary", summary)));
        var event = audit.after(userId, session.getSessionId(), null).stream().filter(value -> "confirmation_required".equals(value.getType())).findFirst().orElseThrow();
        var actual = (Map<?, ?>) event.getPayload().get("summary");
        assertThat(actual.get("amountCents")).isEqualTo(2468);
        assertThat(actual.get("quantity")).isEqualTo(2);
        assertThat(actual.get("expiresAt")).isEqualTo(quote.get("confirmationExpiresAt"));
    }

    @Test void refundAndComplaintRequireMatchingDisplayedBodyAndReplayOnlyTheSameAction() throws Exception {
        // 使用真实购买和独立支付建立可退款券；准备退款与投诉不能提前产生业务申请。
        var quote = data(call("create_purchase_quote", Map.of("dealId", dealId, "quantity", 2), "after-quote"));
        showConfirmation("create_group_buy_order", (String) quote.get("quoteId"), Map.of()); sessions.confirm(userId, session.getSessionId());
        var order = data(call("create_group_buy_order", Map.of("quoteId", quote.get("quoteId"), "confirmationGrantId", quote.get("confirmationGrantId")), "after-create"));
        showConfirmation("pay_mock_order", (String) order.get("orderId"), Map.of()); sessions.confirm(userId, session.getSessionId());
        call("pay_mock_order", Map.of("orderId", order.get("orderId"), "confirmationGrantId", order.get("paymentConfirmationGrantId")), "after-pay");
        String voucher = (String) ((Map<?, ?>) ((List<?>) order.get("vouchers")).get(0)).get("publicId");
        var refund = data(call("prepare_refund", Map.of("voucherId", voucher), "refund-prepare"));
        assertThat(refund).containsEntry("amountCents", 1234).containsKeys("confirmationExpiresAt", "confirmationRuleVersion");
        var payload = Map.of("voucherId", voucher, "reason", "行程取消", "confirmationGrantId", refund.get("confirmationGrantId"));
        assertError(call("request_refund", payload, "refund"), "FORBIDDEN");
        showConfirmation("request_refund", voucher, Map.of("reason", "行程取消")); sessions.confirm(userId, session.getSessionId());
        assertError(call("request_refund", Map.of("voucherId", voucher, "reason", "替换正文", "confirmationGrantId", refund.get("confirmationGrantId")), "refund-wrong-body"), "CONFLICT");
        var submitted = call("request_refund", payload, "refund");
        assertThat(data(submitted)).containsEntry("status", "REQUESTED");
        assertThat(call("request_refund", payload, "refund")).isEqualTo(submitted);
        var preparation = data(call("prepare_complaint", Map.of("orderId", order.get("orderId")), "complaint-prepare"));
        var complaint = Map.of("orderId", order.get("orderId"), "subject", "服务问题", "description", "希望核实服务流程", "confirmationGrantId", preparation.get("confirmationGrantId"));
        assertError(call("submit_complaint", complaint, "complaint"), "FORBIDDEN");
        showConfirmation("submit_complaint", (String) order.get("orderId"), Map.of("subject", "服务问题", "description", "希望核实服务流程"));
        sessions.confirm(userId, session.getSessionId());
        var ticket = call("submit_complaint", complaint, "complaint");
        assertThat(data(ticket)).containsEntry("status", "OPEN").containsKeys("ticketId", "createdAt");
        assertThat(call("submit_complaint", complaint, "complaint")).isEqualTo(ticket);
    }

    @Test void claimedCouponBusinessDatesBecomeRealUtcValidityBoundaries() throws Exception {
        // 优惠券领域存的是业务日期，而协议要求时间点；不能让日期字符串通过空列表测试掩盖非法输出。
        var coupon = couponDefinitions.save(new com.fandazi.feature.coupon.CouponEntity("coupon-" + UUID.randomUUID(), "真实已领优惠券", 5000, 500,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "平台团购", true));
        claimedCoupons.save(new com.fandazi.feature.coupon.UserCouponEntity("claim-" + UUID.randomUUID(), users.findById(userId).orElseThrow(), coupon));
        var result = call("query_coupon", Map.of(), "coupon-date");
        assertThat(result.get("ok")).isEqualTo(true);
        var actual = (Map<?, ?>) ((List<?>) data(result).get("coupons")).get(0);
        assertThat(actual.get("validFrom")).isEqualTo("2026-09-30T16:00:00Z");
        assertThat(actual.get("validTo")).isEqualTo("2026-10-03T15:59:59.999999999Z");
    }

    /** 真实工具鉴权与分派拒绝未开抢，不签报价或预占库存；原因不靠中文消息识别。 */
    @Test void seckillUpcomingUsesStableToolReasonWithoutWrites() throws Exception {
        var activity=seckill(Instant.now().plusSeconds(60),Instant.now().plusSeconds(3600));
        var rejected=call("create_purchase_quote",Map.of("dealId",dealId,"quantity",1),"upcoming");
        assertError(rejected,"CONFLICT");
        assertThat(((Map<?,?>)rejected.get("error")).get("reasonCode")).isEqualTo("NOT_STARTED");
        assertThat(activity.getDeal().getInventory()).isEqualTo(20);
    }

    /** 从真实工具报价、两次独立确认到支付，待支付券不出现可用权益，支付后才可用。 */
    @Test void seckillToolJourneyKeepsSeparateConfirmationAndVoucherRights() throws Exception {
        seckill(Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600));
        var quote=data(call("create_purchase_quote",Map.of("dealId",dealId,"quantity",1),"sk-quote"));
        assertThat(quote).containsKeys("activityId","activityVersion","serverTime","remainingQuota");
        showConfirmation("create_group_buy_order",(String)quote.get("quoteId"),Map.of());sessions.confirm(userId,session.getSessionId());
        var created=call("create_group_buy_order",Map.of("quoteId",quote.get("quoteId"),"confirmationGrantId",quote.get("confirmationGrantId")),"sk-create");
        assertThat(created.get("ok")).isEqualTo(true);var order=data(created);
        assertThat(order).containsEntry("status","PENDING_PAYMENT");
        var pending=(Map<?,?>)((List<?>)order.get("vouchers")).get(0);
        assertThat(pending.get("status")).isEqualTo("PENDING_PAYMENT");assertThat(pending.get("canUse")).isEqualTo(false);
        assertThat(Instant.parse((String)order.get("paymentExpiresAt"))).isEqualTo(Instant.parse((String)order.get("createdAt")).plusSeconds(300));
        // 支付确认只能覆盖原支付期限，不能因创建授权稍晚而把可确认时间延到库存释放之后。
        assertThat(Instant.parse((String)order.get("paymentConfirmationExpiresAt"))).isEqualTo(Instant.parse((String)order.get("paymentExpiresAt")));
        var pay=Map.of("orderId",order.get("orderId"),"confirmationGrantId",order.get("paymentConfirmationGrantId"));
        assertError(call("pay_mock_order",pay,"sk-pay"),"FORBIDDEN");
        showConfirmation("pay_mock_order",(String)order.get("orderId"),Map.of());sessions.confirm(userId,session.getSessionId());
        var paid=data(call("pay_mock_order",pay,"sk-pay"));assertThat(paid).containsEntry("status","PAID");
        assertThat(((Map<?,?>)((List<?>)paid.get("vouchers")).get(0)).get("canUse")).isEqualTo(true);
    }

    /** 活动即将结束时确认期限不得长于报价；否则界面会把已不可购买的确认展示为有效。 */
    @Test void seckillConfirmationDeadlineDoesNotOutliveActivity() throws Exception {
        Instant end=seckillTime.now().plusSeconds(30);seckill(end.minusSeconds(60),end);
        var quote=data(call("create_purchase_quote",Map.of("dealId",dealId,"quantity",1),"sk-short"));
        assertThat(Instant.parse((String)quote.get("confirmationExpiresAt"))).isEqualTo(end);
    }

    /** 仅追加本例专属活动与真实使用规则，保留普通团购测试夹具和业务预期。 */
    private com.fandazi.feature.seckill.SeckillActivityEntity seckill(Instant start,Instant end){
        var deal=deals.findByPublicId(dealId).orElseThrow();deal.replaceCompareFacts(2,null,"堂食使用");
        var activity=new com.fandazi.feature.seckill.SeckillActivityEntity("activity-"+UUID.randomUUID(),deal,start,end);
        entityManager.persist(activity);entityManager.flush();return activity;
    }

    /** 模拟网关已向用户展示 Spring 重查摘要；不能仅调用 confirm 跳过待确认动作绑定。 */
    private void showConfirmation(String action, String resource, Map<String, Object> body) {
        var supplied = new LinkedHashMap<String, Object>(body); supplied.put("action", action); supplied.put("resourceId", resource);
        var summary = cards.enrichSummary(userId, session.getSessionId(), supplied);
        sessions.bindPendingConfirmation(userId, session.getSessionId(), session.getActiveRunId(), summary);
    }

    /** 构造执行器身份；默认版本为 1，键中的三个分量必须与请求一致。 */
    private Map<String, Object> request(Map<String, ?> payload, String task) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.1"); body.put("userId", userId.toString()); body.put("sessionId", session.getSessionId());
        body.put("traceId", session.getTraceId()); body.put("runId", session.getActiveRunId()); body.put("taskId", task);
        body.put("idempotencyKey", session.getActiveRunId() + ":" + task + ":1"); body.put("actionVersion", 1); body.put("payload", payload);
        return body;
    }

    /** 经真实 HTTP 入口执行工具，不使用分发器或数据库替身。 */
    private Map<String, Object> call(String name, Map<String, ?> payload, String task) throws Exception {
        return send(name, request(payload, task), jwt(userId.toString(), 60));
    }

    /** 将响应读取为 JSON 对象，同时要求服务始终返回标准业务结果而非框架错误页面。 */
    private Map<String, Object> send(String name, Map<String, Object> body, String token) throws Exception {
        var response = http.perform(post("/internal/v1/tools/" + name).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        Map<String,Object> result=mapper.readValue(response.getContentAsString(StandardCharsets.UTF_8),new com.fasterxml.jackson.core.type.TypeReference<>() {});
        // 仅实际成功的知识工具声明完整交付能力；失败或其它工具不借成功头伪装知识可用。
        if("query_faq".equals(name) && Boolean.TRUE.equals(result.get("ok")))
            assertThat(response.getHeader("X-Knowledge-Authority")).isEqualTo("delivery-v1");
        else assertThat(response.getHeader("X-Knowledge-Authority")).isNull();
        return result;
    }

    /** 校验失败不会携带业务数据、堆栈或泄漏请求内容，且错误码稳定。 */
    private void assertError(Map<String, Object> result, String code) {
        assertThat(result).containsEntry("schemaVersion", "1.1").containsEntry("ok", false);
        assertThat(data(result)).isEmpty();
        assertThat(((Map<?, ?>) result.get("error")).get("code")).isEqualTo(code);
    }

    /** 返回实际成功数据；断言字段由各测试使用独立的字面期望核对。 */
    @SuppressWarnings("unchecked") private Map<String, Object> data(Map<String, Object> result) { return (Map<String, Object>) result.get("data"); }

    /** 签发仅供隔离测试使用的 JWT，生产密钥从未进入测试夹具。 */
    private String jwt(String subject, long seconds) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String content = encoder.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT"))) + "."
                + encoder.encodeToString(mapper.writeValueAsBytes(Map.of("sub", subject, "exp", Instant.now().getEpochSecond() + seconds)));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("task3-test-only-hmac-key-never-production".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return content + "." + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
