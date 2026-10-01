// 帮助中心条目测试：保证售后页面展示的常见问题来自后端，并与已实现能力一致。
package com.fandazi.feature.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 校验 FAQ 的覆盖范围和关键词查询，防止端侧为了补齐设计内容而写死业务答案。 */
class FaqServiceTest {
    private final FaqService service = new FaqService();

    /** 购买、用券、退款、账号四类帮助必须由同一接口返回，供帮助页逐项展开。 */
    @Test
    void exposesRequiredGroupBuyingHelpTopics() {
        List<Map<String, String>> items = service.query(null);
        assertThat(items).extracting(item -> item.get("id"))
                .contains("purchase-flow", "voucher-use", "refund-eligibility", "account-login", "platform-rules");
        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("question")).isNotBlank();
            assertThat(item.get("answer")).isNotBlank();
        });
    }

    /** 搜索账号问题只返回相关条目，避免客户端自行维护过滤后的固定列表。 */
    @Test
    void filtersAccountQuestionByKeyword() {
        assertThat(service.query("账号")).extracting(item -> item.get("id"))
                .contains("account-login")
                .doesNotContain("purchase-flow", "refund-eligibility");
    }

    /** 平台规则可由关键词搜索到，但不得把尚未实现的核销能力描述为已上线。 */
    @Test
    void exposesPlatformRulesWithoutPromisingRedemption() {
        assertThat(service.query("平台规则")).singleElement().satisfies(item -> {
            assertThat(item.get("id")).isEqualTo("platform-rules");
            assertThat(item.get("answer")).contains("模拟支付").doesNotContain("已开放核销");
        });
    }
}
