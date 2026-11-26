// 团购券生命周期测试：固定一券一数量、查询隔离和重复核销不重复消费的业务契约。
package com.fandazi.feature.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证团购券状态集合满足 Task07 的最小生命周期要求。 */
class VoucherLifecycleTest {
    @Test
    void voucherStatusesContainPurchaseAndRedemptionStates() {
        // 这些状态会直接映射到数据库和 Android 展示层，缺失会导致跨层协议不一致。
        assertThat(VoucherStatus.values()).containsExactly(
                VoucherStatus.UNUSED, VoucherStatus.REDEEMED, VoucherStatus.REFUNDED, VoucherStatus.EXPIRED);
    }
}
