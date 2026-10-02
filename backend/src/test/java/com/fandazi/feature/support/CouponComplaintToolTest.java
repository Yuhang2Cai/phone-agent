// 售后工具测试：验证优惠券按用户隔离，投诉必须关联本人订单和一次性确认。
package com.fandazi.feature.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.agent.AgentMode;
import com.fandazi.feature.agent.AgentSessionService;
import com.fandazi.feature.agent.AgentV1ToolBridge;
import com.fandazi.feature.agent.InternalV1ToolRequest;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.coupon.CouponEntity;
import com.fandazi.feature.coupon.CouponRepository;
import com.fandazi.feature.coupon.CouponService;
import com.fandazi.feature.coupon.UserCouponEntity;
import com.fandazi.feature.coupon.UserCouponRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 只读与写入工具必须遵守不同的身份、确认和数据边界。 */
// 读写工具测试不与旧配送/鉴权测试共享 H2 种子，保证全量套件可重复。
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:task8-support;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@Transactional
class CouponComplaintToolTest {
    // 测试仍以确定性别名选择场景，实际 API 和断言使用数据库随机 UUID，防止开放旧枚举入口。
    @Autowired private com.fandazi.core.identity.PublicIdAliases publicIds;
    @Autowired private CouponService coupons;
    @Autowired private ComplaintService complaints;
    @Autowired private CouponRepository couponRepository;
    @Autowired private UserCouponRepository userCoupons;
    @Autowired private UserRepository users;
    @Autowired private ConfirmationGrantService grants;
    @Autowired private AgentSessionService sessions;
    @Autowired private AgentV1ToolBridge tools;

    /** 只返回当前用户领过的券，不把平台全量券误认为个人优惠券。 */
    @Test
    void couponsAreScopedToCurrentUser() {
        var alice = users.findByUsername("seed_alice").orElseThrow();
        var stranger = users.save(new UserEntity("coupon-stranger-" + UUID.randomUUID(), "coupon-stranger@example.com", "hash"));
        CouponEntity coupon = couponRepository.save(new CouponEntity("coupon-test-" + UUID.randomUUID(), "团购立减", 5000, 500,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), "指定团购", true));
        userCoupons.save(new UserCouponEntity(UUID.randomUUID().toString(), alice, coupon));

        assertThat(coupons.list(alice.getId())).anySatisfy(item -> assertThat(item.get("couponId")).isEqualTo(coupon.getPublicId()));
        assertThat(coupons.list(stranger.getId())).noneSatisfy(item -> assertThat(item.get("couponId")).isEqualTo(coupon.getPublicId()));
    }

    /** 不能以陌生人的订单号提交投诉，也不能拿购买确认凭证冒充投诉确认。 */
    @Test
    void complaintRequiresOwnedOrderAndMatchingGrantAction() {
        var stranger = users.save(new UserEntity("complaint-stranger-" + UUID.randomUUID(), "complaint-stranger@example.com", "hash"));
        String sessionId = "complaint-" + UUID.randomUUID();
        assertThatThrownBy(() -> complaints.prepare(stranger.getId(), sessionId, publicIds.require("order", "group-order-paid")))
                .isInstanceOf(DomainException.class).hasMessageContaining("订单不存在");

        var alice = users.findByUsername("seed_alice").orElseThrow();
        String wrongAction = grants.issue(alice.getId(), sessionId, publicIds.require("order", "group-order-paid"), 0, "complaint-v1").getGrantId();
        assertThatThrownBy(() -> complaints.submit(alice.getId(), sessionId, publicIds.require("order", "group-order-paid"), "门店服务", "到店体验问题", wrongAction))
                .isInstanceOf(DomainException.class).hasMessageContaining("动作不匹配");
        String grant = complaints.prepare(alice.getId(), sessionId, publicIds.require("order", "group-order-paid")).get("confirmationGrant").toString();
        var submitted = complaints.submit(alice.getId(), sessionId, publicIds.require("order", "group-order-paid"), "门店服务", "到店体验问题", grant);
        assertThat(submitted.get("status")).isEqualTo("OPEN");
    }

    /** 模型能准备凭证不等于用户授权，写工具必须再检查会话确认状态。 */
    @Test
    void agentComplaintWriteWaitsForExplicitSessionConfirmation() {
        var alice = users.findByUsername("seed_alice").orElseThrow();
        String sessionId = "complaint-agent-" + UUID.randomUUID();
        var session = sessions.start(alice.getId(), sessionId, AgentMode.CONFIRM, "trace");
        String orderId = publicIds.require("order", "group-order-paid");
        String grant = complaints.prepare(alice.getId(), sessionId, orderId).get("confirmationGrant").toString();
        var payload = java.util.Map.<String,Object>of("orderId", orderId, "subject", "门店服务", "description", "到店体验问题",
                "confirmationGrantId", grant);
        var request = new InternalV1ToolRequest("1.1", alice.getId().toString(), sessionId, "trace",
                session.getActiveRunId(), "complaint", session.getActiveRunId()+":complaint:1", 1, payload);
        // 已准备凭证仍未授权，V1真实执行器必须拒绝，不能依赖已退役旧工具证明新边界。
        assertThatThrownBy(() -> tools.execute("submit_complaint", request))
                .isInstanceOf(DomainException.class).hasMessageContaining("明确确认");
        sessions.bindPendingConfirmation(alice.getId(), sessionId, session.getActiveRunId(),
                java.util.Map.of("action","submit_complaint", "resourceId",orderId, "ruleVersion","complaint-v1",
                        "amountCents",0, "subject","门店服务", "description","到店体验问题"));
        sessions.confirm(alice.getId(), sessionId);
        var submitted = tools.execute("submit_complaint", request);
        assertThat(submitted.get("status")).isEqualTo("OPEN");
        // 相同动作键重放已提交事实而不是再次消费凭证，符合新执行器幂等契约。
        assertThat(tools.execute("submit_complaint", request)).isEqualTo(submitted);
    }

    /** 只读工具返回值必须可写入审计 JSON，不能因 Java 时间对象导致查询失败。 */
    @Test
    void afterSaleReadToolsProduceAuditableResults() {
        var alice = users.findByUsername("seed_alice").orElseThrow();
        String sessionId = "after-sale-read-" + UUID.randomUUID();
        var session = sessions.start(alice.getId(), sessionId, AgentMode.CONFIRM, "trace");
        for (String name : java.util.List.of("list_user_orders", "list_user_vouchers", "query_coupon", "query_faq", "get_order_detail")) {
            java.util.Map<String, Object> payload = name.equals("get_order_detail")
                    ? java.util.Map.of("orderId", publicIds.require("order", "group-order-paid")) : name.equals("query_faq") ? java.util.Map.of("question","退款规则") : java.util.Map.of();
            // 按新run/task/version键执行真实只读域并校验数据可审计，旧工具不再装配。
            var request = new InternalV1ToolRequest("1.1", alice.getId().toString(), sessionId, "trace",
                    session.getActiveRunId(), name, session.getActiveRunId()+":"+name+":1", 1, payload);
            assertThat(tools.execute(name, request)).isNotEmpty();
        }
    }
}
