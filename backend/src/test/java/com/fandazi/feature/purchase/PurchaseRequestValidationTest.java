// 购买入口边界测试：不访问在线业务库，保证缺少报价身份的历史字段请求无法绕开快照确认。
package com.fandazi.feature.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import com.fandazi.feature.grouporder.GroupBuyOrderController;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** 校验公开创建请求必须携带报价，防止未来回退到仅套餐/数量的旧购买入口。 */
class PurchaseRequestValidationTest {
    @Test
    void legacyDealOnlyRequestRequiresQuoteIdentity() {
        // 历史客户端给齐其他字段也不能跳过报价；HTTP @Valid 会在写服务之前拒绝该载荷。
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new GroupBuyOrderController.CreateRequest(null, "old-deal", 1, "grant", "session", "key");
            assertThat(factory.getValidator().validate(request))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("quoteId"));
        }
    }
}
