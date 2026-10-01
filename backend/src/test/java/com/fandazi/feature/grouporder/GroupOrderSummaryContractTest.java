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
        "spring.datasource.url=jdbc:h2:mem:task8-order-summary;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@Transactional
class GroupOrderSummaryContractTest {
    @Autowired private GroupBuyOrderService orders;
    @Autowired private UserRepository users;

    /** 券摘要要能直接形成正式列表和详情，不得由端上写死补全。 */
    @Test
    void voucherSummaryCarriesBackendOwnedDisplayFields() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        Map<String, Object> order = orders.detail(userId, "group-order-paid");
        // 评价页依赖真实下单时间，协议缺失时不允许退化为长订单号。
        assertThat(order.get("createdAt")).isInstanceOf(String.class).asString().isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = ((java.util.List<Map<String, Object>>) order.get("vouchers")).get(0);
        assertThat(voucher).containsKeys("orderId", "dealId", "dealTitle", "storeId", "storeName");
    }
}
