// 售后资格测试：以真实团购券种子验证归属、状态和确认凭证三道边界。
package com.fandazi.feature.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.voucher.VoucherService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 确保已核销、他人和未确认的团购券不能产生退款申请。 */
// 使用独立内存库，避免其他模块的清理型测试改变本组团购种子。
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:task8-refund;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@Transactional
class RefundEligibilityTest {
    @Autowired private RefundService refunds;
    @Autowired private UserRepository users;
    @Autowired private GroupBuyOrderRepository orders;
    @Autowired private GroupBuyOrderService orderService;
    @Autowired private VoucherService voucherService;
    @Autowired private ConfirmationGrantService grants;

    /** 已核销券即使持有有效确认凭证，也不能回退成可退款状态。 */
    @Test
    void redeemedVoucherCannotBeRefunded() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        var voucher = orders.findByPublicId("group-order-used").orElseThrow().getVouchers().get(0);
        String sessionId = "refund-redeemed-" + UUID.randomUUID();
        String grant = grants.issueForAction(userId, sessionId, "REQUEST_REFUND", voucher.getPublicId(), 0, "refund-v1").getGrantId();

        assertThatThrownBy(() -> refunds.request(userId, sessionId, voucher.getPublicId(), "行程变化", grant))
                .isInstanceOf(DomainException.class).hasMessageContaining("已核销团购券不可退款");
    }

    /** 未使用券只有得到该券专属的一次性确认凭证才可申请，重复申请不得生成第二条记录。 */
    @Test
    void unusedVoucherRequiresGrantAndAllowsOnlyOneRequest() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        var voucher = orders.findByPublicId("group-order-paid").orElseThrow().getVouchers().get(0);
        String sessionId = "refund-valid-" + UUID.randomUUID();
        assertThatThrownBy(() -> refunds.request(userId, sessionId, voucher.getPublicId(), "行程变化", "invalid"))
                .isInstanceOf(DomainException.class).hasMessageContaining("确认凭证");

        // 零金额凭证不能授权已付款券退款；旧夹具曾省略金额绑定，现明确覆盖该真实安全缺口。
        String wrongAmount = grants.issueForAction(userId, sessionId, "REQUEST_REFUND", voucher.getPublicId(), 0, "refund-v1").getGrantId();
        assertThatThrownBy(() -> refunds.request(userId, sessionId, voucher.getPublicId(), "行程变化", wrongAmount))
                .isInstanceOf(DomainException.class).hasMessageContaining("业务事实已变化");
        // 正常路径必须消费真实准备接口签发的金额凭证，而非测试自行构造不匹配的默认金额。
        String grant = (String) refunds.prepare(userId, sessionId, voucher.getPublicId()).get("confirmationGrant");
        var result = refunds.request(userId, sessionId, voucher.getPublicId(), "行程变化", grant);
        assertThat(result.get("status")).isEqualTo("REQUESTED");
        assertThatThrownBy(() -> refunds.request(userId, sessionId, voucher.getPublicId(), "重复申请", grant))
                .isInstanceOf(DomainException.class);
    }

    /** 用户 ID 在服务端决定归属，不能用别人的券 ID 越权申请。 */
    @Test
    void anotherUsersVoucherIsNotRefundable() {
        Long strangerId = users.save(new UserEntity("refund-stranger-" + UUID.randomUUID(), "refund-stranger@example.com", "hash")).getId();
        var voucher = orders.findByPublicId("group-order-paid").orElseThrow().getVouchers().get(0);
        assertThatThrownBy(() -> refunds.eligibility(strangerId, voucher.getPublicId()))
                .isInstanceOf(DomainException.class).hasMessageContaining("团购券不存在");
    }

    /** 待支付订单尚未发生付款，即使券状态为未使用也不能申请退款。 */
    @Test
    void unpaidOrderVoucherCannotBeRefunded() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        var voucher = orders.findByPublicId("group-order-pending").orElseThrow().getVouchers().get(0);
        assertThatThrownBy(() -> refunds.eligibility(userId, voucher.getPublicId()))
                .isInstanceOf(DomainException.class).hasMessageContaining("未支付订单不可退款");
    }

    /** 服务端资格字段必须在申请前后同步变化，端侧不能只靠 `UNUSED` 状态猜测。 */
    @Test
    void readModelsExposeRefundEligibilityWithoutClientGuessing() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        var paid = orders.findByPublicId("group-order-paid").orElseThrow().getVouchers().get(0);
        var pending = orders.findByPublicId("group-order-pending").orElseThrow().getVouchers().get(0);
        var used = orders.findByPublicId("group-order-used").orElseThrow().getVouchers().get(0);

        assertThat(voucherService.detail(userId, paid.getPublicId()).get("canRefund")).isEqualTo(true);
        assertThat(voucherService.detail(userId, pending.getPublicId()).get("canRefund")).isEqualTo(false);
        assertThat(voucherService.detail(userId, used.getPublicId()).get("canRefund")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var orderVouchers = (java.util.List<java.util.Map<String, Object>>) orderService.detail(userId, "group-order-paid").get("vouchers");
        assertThat(orderVouchers.get(0).get("canRefund")).isEqualTo(true);

        String sessionId = "refund-display-" + UUID.randomUUID();
        String grant = refunds.prepare(userId, sessionId, paid.getPublicId()).get("confirmationGrant").toString();
        refunds.request(userId, sessionId, paid.getPublicId(), "行程变化", grant);
        assertThat(voucherService.detail(userId, paid.getPublicId()).get("canRefund")).isEqualTo(false);
    }
}
