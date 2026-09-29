// 跨仓库 Agent 契约测试：先验证手机项目能够读取权威工具契约，再交给实现代码使用。
package com.fandazi.feature.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证手机项目维护的跨服务契约具备完整的工具目录。
 *
 * <p>这里暂时只做轻量文本校验，不把契约解析逻辑绑定到业务 Spring Bean，
 * 这样契约缺失时可以得到直接、稳定的失败信息。</p>
 */
class AgentContractSchemaTest {
    private static final Pattern TOOL_NAME = Pattern.compile("^\\s*- name: ([a-z_]+)\\s*$", Pattern.MULTILINE);

    @Test
    void agentToolContractContainsEveryApprovedTool() throws IOException {
        // 测试从 Maven 的 backend 工作目录回到手机项目根目录，读取权威契约文件。
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path contract = projectRoot.resolve("contracts").resolve("agent-tools-v1.yaml");
        String yaml = Files.readString(contract, StandardCharsets.UTF_8);

        Set<String> expected = Set.of(
                "search_stores", "get_store_detail", "list_deal_packages",
                "get_deal_detail", "search_reviews", "list_user_orders",
                "get_order_detail", "list_user_vouchers", "create_purchase_quote",
                "create_group_buy_order", "pay_mock_order", "request_refund",
                "query_coupon", "query_faq", "submit_complaint"
        );

        // 契约必须完整覆盖批准工具，避免 Agent 依据过期或不完整的工具目录规划任务。
        Set<String> actual = TOOL_NAME.matcher(yaml).results()
                .map(match -> match.group(1))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
