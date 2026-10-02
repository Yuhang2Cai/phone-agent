// 帮助中心条目测试：保证售后页面展示的常见问题来自后端，并与已实现能力一致。
package com.fandazi.feature.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 校验 FAQ 的覆盖范围和关键词查询，防止端侧为了补齐设计内容而写死业务答案。 */
class FaqServiceTest {
    private final FaqService service = new FaqService();

    /** Agent 提交完整自然问题，不应要求原句连续出现在标准问题或答案中。 */
    @Test
    void naturalRefundQuestionFindsAuthoritativeAnswerWithoutChangingPageSearch() {
        assertThat(service.query("请问退款规则是什么？")).singleElement()
                .satisfies(item -> assertThat(item).isEqualTo(service.query("", "refund-eligibility").get(0)));
        // 页面仍执行用户显式关键词与分类交集，不能被 Agent 的主题召回扩宽。
        assertThat(service.query("退款规则", "purchase-flow")).isEmpty();
        assertThat(service.query("量子力学是什么")).isEmpty();
    }

    /** 中文主题、标准 ID、冲突和未知主题必须遵守同一知识边界，不能任意扩宽召回。 */
    @Test
    void agentTopicFiltersKnownAnswersAndRejectsUnknownTopics() {
        var expected = service.query("", "refund-eligibility");
        assertThat(service.queryForAgent("查退款规则", "退款")).isEqualTo(expected);
        assertThat(service.queryForAgent("查退款规则", "refund-eligibility")).isEqualTo(expected);
        assertThat(service.queryForAgent("查退款规则", "refund")).isEqualTo(expected);
        assertThat(service.queryForAgent("查退款规则", "purchase")).isEmpty();
        assertThat(service.queryForAgent("查退款规则", "unknown")).isEmpty();
        assertThat(service.queryForAgent("查退款规则", "fund")).isEmpty();
    }

    /** 页面分类由服务端目录给出；分类和搜索必须取交集，不能在安卓写死筛选业务数据。 */
    @Test
    void categoryAndKeywordUseTheSameAuthoritativeCatalog() {
        assertThat(service.categories()).extracting(item -> item.get("id"))
                .contains("purchase-flow", "voucher-use", "refund-eligibility", "account-login");
        assertThat(service.query("", "purchase-flow")).singleElement()
                .satisfies(item -> assertThat(item.get("id")).isEqualTo("purchase-flow"));
        assertThat(service.query("账号", "purchase-flow")).isEmpty();
        assertThat(service.query("", "unknown-category")).isEmpty();
        // 工具原有严格问答结构不变；页面目录不混入 query_faq 的成功载荷。
        assertThat(service.query(null)).allSatisfy(item ->
                assertThat(item.keySet()).containsExactlyInAnyOrder("id", "question", "answer"));
    }

    /** 帮助文案必须与真实资格一致，不承诺未付款、过期或取消订单可以退款。 */
    @Test
    void refundHelpIncludesPaymentAndExpiryRestrictions() {
        assertThat(service.query("", "refund-eligibility").get(0).get("answer"))
                .contains("已支付", "有效期", "未核销", "未申请");
        assertThat(service.query("", "coupon-use").get(0).get("answer"))
                .doesNotContain("我的优惠券").contains("AI 助手");
    }

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
