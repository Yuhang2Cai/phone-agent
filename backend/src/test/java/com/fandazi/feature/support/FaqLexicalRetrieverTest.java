// AG08词项召回反例：知识召回可以扩展同义表达，但不得替代第一层意图识别。
package com.fandazi.feature.support;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 检查真实FAQ缺口：漏同义问题、组合丢来源、外部领域误命中。 */
class FaqLexicalRetrieverTest {
    /** 接口尚不存在时回到原服务，让失败落在具体召回断言而不是编译或类加载异常。 */
    @SuppressWarnings("unchecked")
    private List<Map<String,String>> query(String question) throws Exception {
        var method=java.util.Arrays.stream(FaqService.class.getDeclaredMethods())
                .filter(item->item.getName().equals("queryReviewed") && item.getParameterCount()==2).findFirst();
        var service=new FaqService();
        if(method.isEmpty())return service.queryForAgent(question,null);
        method.get().setAccessible(true);
        return (List<Map<String,String>>)method.get().invoke(service,question,null);
    }

    /** 常见语义表达应召回对应审核条目，不要求用户使用静态主题词。 */
    @Test void findsParaphrase() throws Exception {
        assertThat(query("从选择套餐到获得券要经过哪些步骤？")).extracting(row->row.get("id")).contains("purchase-flow");
        assertThat(query("已经点评过同一订单还能再次打分吗？")).extracting(row->row.get("id")).contains("review-eligibility");
    }

    /** 购买和核销是不同的知识事实，不允许仅返回用券条目。 */
    @Test void retainsBothCombinationSources() throws Exception {
        assertThat(query("怎么买团购，买完现在能去店里核销吗？")).extracting(row->row.get("id"))
                .contains("purchase-flow","voucher-use");
    }

    /** 外部领域即使出现退款或评价字样，也不能拿平台规则冒充正确答案。 */
    @Test void rejectsExplicitExternalDomain() throws Exception {
        assertThat(query("企业所得税退款如何申报？")).isEmpty();
        assertThat(query("评价一下量子力学的多世界解释")).isEmpty();
        assertThat(query("银行卡挂失后银行退款多久到账？")).isEmpty();
    }

    /** 明确平台模拟交易中的银行卡问法仍可解释，不应把所有银行卡词机械屏蔽。 */
    @Test void keepsSupportedMockPaymentExplanation() throws Exception {
        assertThat(query("购买套餐会真实扣银行卡的钱吗？")).extracting(row->row.get("id")).contains("purchase-flow");
    }
}
