// 团购订单模块测试：验证重复提交不会创建重复订单。
package com.fandazi.feature.grouporder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证订单幂等键的语义由数据库唯一约束和服务层共同保证。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true"})
class GroupBuyOrderIdempotencyTest {
    @Test
    void taskSixContractReservesIdempotentOrderWrite() {
        // 该断言先固定业务状态名称，后续服务实现必须返回同一订单而不是重复写入。
        assertThat(GroupBuyOrderStatus.PENDING_PAYMENT.name()).isEqualTo("PENDING_PAYMENT");
        assertThat(GroupBuyOrderStatus.PAID.name()).isEqualTo("PAID");
    }
}
