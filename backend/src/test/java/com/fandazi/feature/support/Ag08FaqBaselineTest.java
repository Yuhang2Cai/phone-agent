// AG08离线组件基线：调用真实FaqService，不复写其匹配算法，不声称已验证HTTP或模型路由。
package com.fandazi.feature.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅在显式指定冻结输入和新输出时测量；正常业务测试不把未执行的基线采集计为通过。 */
@EnabledIfSystemProperty(named="ag08.baseline.input", matches=".+")
class Ag08FaqBaselineTest {
    /** 每题保存真实返回与组件耗时；拒绝覆盖旧证据，语义正确性由另行逐主张核查。 */
    @Test
    void captureOriginalFaqComponentResponses() throws Exception {
        var mapper = new ObjectMapper();
        var input = Path.of(System.getProperty("ag08.baseline.input"));
        var output = Path.of(System.getProperty("ag08.baseline.output"));
        assertThat(Files.exists(output)).as("原始证据不得覆盖").isFalse();
        var root = mapper.readTree(input.toFile());
        assertThat(root.path("cases").size()).as("冻结输入不得为空").isGreaterThan(0);
        var service = new FaqService();
        var catalogPath = System.getProperty("ag08.baseline.catalog");
        // 同语料对照仍调用同一真实服务，仅替换已冻结知识内容，不复制或重写原匹配逻辑。
        if (catalogPath != null && !catalogPath.isBlank()) {
            var catalog = mapper.readTree(Path.of(catalogPath).toFile()).path("rows");
            var rows = new ArrayList<Map<String,String>>();
            for (var row : catalog) rows.add(Map.of("id",row.path("id").asText(),"question",row.path("question").asText(),"answer",row.path("answer").asText()));
            service = new FaqService(rows);
        }
        var observations = new ArrayList<Map<String,Object>>();
        // 顺序逐题调用，避免并发改变测量条件；本轮数字仅为本机组件观察，不代表服务容量。
        for (var item : root.path("cases")) {
            var question = item.path("question").asText();
            long start = System.nanoTime();
            var rows = "reviewed-lexical".equals(System.getProperty("ag08.baseline.mode"))
                    ? service.queryReviewed(question, null) : service.queryForAgent(question, null);
            long elapsed = System.nanoTime() - start;
            var record = new LinkedHashMap<String,Object>();
            record.put("id", item.path("id").asText());
            record.put("question", question);
            record.put("rows", rows);
            record.put("durationNanos", elapsed);
            observations.add(record);
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("kind", "real_FaqService_component_not_HTTP");
        result.put("observations", observations);
        result.put("catalog", service.query(null));
        Files.createDirectories(output.getParent());
        // CREATE_NEW保留首轮输出；重复运行必须指定独立代次，不拼接失败与复验。
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(observations).hasSize(root.path("cases").size());
    }
}
