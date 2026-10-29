// AG08语料控制测试：替换知识内容时保持同一匹配实现，保证公平对照与条目快照隔离。
package com.fandazi.feature.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证受控语料注入，防止同语料对照仍偷偷读取旧静态目录或调用方修改快照。 */
class FaqCatalogIsolationTest {
    /** 反射仅为先建立“缺少受控语料入口”的指定断言，不以编译错误冒充预期失败。 */
    @Test
    void suppliedCatalogIsUsedAndDefensivelyCopied() throws Exception {
        var constructor = java.util.Arrays.stream(FaqService.class.getDeclaredConstructors())
                .filter(item -> java.util.Arrays.equals(item.getParameterTypes(), new Class<?>[]{List.class}))
                .findFirst();
        assertThat(constructor).as("同语料基线需要可注入的真实FAQ语料入口").isPresent();
        var rows = new ArrayList<Map<String,String>>();
        rows.add(Map.of("id", "refund-eligibility", "question", "测试退款资格", "answer", "审核版本要求保留所有条件"));
        constructor.get().setAccessible(true);
        var service = (FaqService) constructor.get().newInstance(rows);
        rows.clear();
        assertThat(service.queryForAgent("请解释退款规则", null)).singleElement()
                .satisfies(row -> assertThat(row.get("answer")).isEqualTo("审核版本要求保留所有条件"));
        assertThat(service.categories()).hasSize(1);
        assertThat(new FaqService().categories()).hasSize(8);
    }
}
