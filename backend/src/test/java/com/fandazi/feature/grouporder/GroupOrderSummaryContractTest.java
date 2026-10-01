// 订单协议测试：确保订单列表中的券摘要满足 Android 真实数据解析契约。
package com.fandazi.feature.grouporder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fandazi.feature.auth.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 订单列表不能返回缺少门店、套餐和订单关联的半成品券卡。 */
// 合同测试专用数据源防止跨类清理影响订单详情。
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:task8-order-summary;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@Transactional
class GroupOrderSummaryContractTest {
    // 测试仍以确定性别名选择场景，实际 API 和断言使用数据库随机 UUID，防止开放旧枚举入口。
    @Autowired private com.fandazi.core.identity.PublicIdAliases publicIds;
    @Autowired private GroupBuyOrderService orders;
    @Autowired private UserRepository users;
    @Autowired private GroupBuyOrderRepository entities;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    /** JPA 真实插入和支付必须读写 DECIMAL 权威列，legacy 列保持 null，不能只添加不使用的新列。 */
    @Test
    void newOrdersPersistAuthoritativeDecimalAndPaymentSnapshotOnly() {
        var source = entities.findByPublicId(publicIds.require("order", "group-order-paid")).orElseThrow();
        var order = entities.saveAndFlush(new GroupBuyOrderEntity(java.util.UUID.randomUUID().toString(), source.getUser(),
                source.getStore(), source.getDeal(), 3, 10001, GroupBuyOrderStatus.PENDING_PAYMENT,
                com.fandazi.core.time.BusinessTime.now().plusDays(10)));
        var before = jdbc.queryForMap("SELECT total_amount,total_cents,paid_total_amount FROM group_buy_orders WHERE id=?", order.getId());
        assertThat((java.math.BigDecimal) before.get("total_amount")).isEqualByComparingTo("100.01");
        assertThat(before.get("total_cents")).isNull(); assertThat(before.get("paid_total_amount")).isNull();
        // 先清空一级缓存再查询，确保金额来自转换器读取的数据库值，而非对象构造后的内存字段。
        Long owner = source.getUser().getId(); String publicId = order.getPublicId();
        entityManager.clear();
        assertThat(orders.detail(owner, publicId).get("totalCents")).isEqualTo(10001);
        orders.pay(owner, publicId, "decimal-payment"); entityManager.flush(); entityManager.clear();
        var paid = jdbc.queryForMap("SELECT total_amount,total_cents,paid_total_amount,paid_at FROM group_buy_orders WHERE public_id=?", publicId);
        assertThat((java.math.BigDecimal) paid.get("paid_total_amount")).isEqualByComparingTo("100.01");
        assertThat(paid.get("paid_at")).isNotNull(); assertThat(paid.get("total_cents")).isNull();
        @SuppressWarnings("unchecked") var vouchers = (java.util.List<Map<String, Object>>) orders.detail(owner, publicId).get("vouchers");
        assertThat(vouchers).extracting(voucher -> voucher.get("amountCents")).containsExactly(3334, 3334, 3333);
        // 规范化金额不依赖套餐现价，单券分摊写入后仍可稳定重新读取。
        assertThat(entities.findByPublicId(publicId).orElseThrow().getPaidTotalCents()).isEqualTo(10001);
    }

    /** 时间必须表示可解析的 UTC 瞬间，券金额和图片缺省值必须由服务端明确给出。 */
    @Test
    void summaryProvidesUtcNullableImagesAndPaymentAllocation() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        var paid = orders.detail(userId, publicIds.require("order", "group-order-paid"));
        // 带 Z 的输出防止 Android 将上海本地时间误当 UTC；缺图仍需保留字段而非静态补图。
        assertThat(paid).containsKeys("imageUrl", "imageAlt");
        assertThat(paid.get("createdAt").toString()).endsWith("Z");
        java.time.Instant.parse(paid.get("createdAt").toString());
        @SuppressWarnings("unchecked")
        var vouchers = (java.util.List<Map<String, Object>>) paid.get("vouchers");
        assertThat(vouchers).allSatisfy(voucher -> {
            assertThat(voucher).containsKeys("amountCents", "createdAt", "imageUrl", "imageAlt");
            assertThat(voucher.get("amountCents")).isEqualTo(12800);
            assertThat(voucher.get("expiresAt").toString()).endsWith("Z");
        });
        @SuppressWarnings("unchecked")
        var pending = (java.util.List<Map<String, Object>>) orders.detail(userId, publicIds.require("order", "group-order-pending")).get("vouchers");
        // 没有付款事实时不虚构零元退款金额，也不能开放入口。
        assertThat(pending.get(0)).containsEntry("amountCents", null).containsEntry("canRefund", false);
    }

    /** 券摘要要能直接形成正式列表和详情，不得由端上写死补全。 */
    @Test
    void voucherSummaryCarriesBackendOwnedDisplayFields() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        Map<String, Object> order = orders.detail(userId, publicIds.require("order", "group-order-paid"));
        // 评价页依赖真实下单时间，协议缺失时不允许退化为长订单号。
        assertThat(order.get("createdAt")).isInstanceOf(String.class).asString().isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = ((java.util.List<Map<String, Object>>) order.get("vouchers")).get(0);
        assertThat(voucher).containsKeys("orderId", "dealId", "dealTitle", "storeId", "storeName");
    }
}
