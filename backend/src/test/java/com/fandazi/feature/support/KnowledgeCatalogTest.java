package com.fandazi.feature.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** 真实本地JDBC目录反例：先证明发布/来源/失效约束，H2通过不替代真MySQL并发验收。 */
class KnowledgeCatalogTest {
    /** 每例独立数据库，避免撤回状态或许可污染其它测试。 */
    private KnowledgeCatalog catalog() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        // 执行交付迁移原文，不在测试中另造一套简化表结构。
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/migration/V2026102901__knowledge_authority.sql")).execute(source);
        return new KnowledgeCatalog(source, new DataSourceTransactionManager(source));
    }
    /** 合成规则保持完整条件与否定，不引用任何真实账户数据。 */
    private KnowledgeCatalog.Entry entry(String version, String body, Instant until) {
        return new KnowledgeCatalog.Entry("refund", version, "合成退款规则", "退款条件是什么？", body,
                Instant.parse("2020-01-01T00:00:00Z"), until, List.of("退款"));
    }
    /** 可信身份与用户可见文本分开；不同用户不能借用相同动作键。 */
    private KnowledgeCatalog.Scope scope(String user) {
        return new KnowledgeCatalog.Scope("run:faq:1", user, "session", "run", "faq");
    }
    @Test void published_rule_is_delivered_verbatim_with_source() {
        // 防止输出只剩结论，丢失关键限制或伪造来源版本。
        var catalog = catalog();
        catalog.publish("r1", "合成审核记录", List.of(entry("1", "只记录申请，不承诺资金到账。", null)));
        assertEquals(1, catalog.current().size(), "已审核发布应能读取");
        var rows = catalog.bind(scope("1"), List.of("refund"));
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).get("answer").contains("只记录申请，不承诺资金到账。"));
        assertTrue(rows.get(0).get("answer").contains("合成退款规则"));
        var permit = assertDoesNotThrow(() -> catalog.permit(scope("1"), "event-1"));
        assertEquals(rows, permit.answers());
        assertNotNull(permit.permittedAt());
    }
    @Test void revocation_survives_rollback_and_rejects_old_binding() {
        // 先建立真实可交付前提，再撤回与回滚；不能以始终拒绝伪装安全测试通过。
        var catalog = catalog();
        catalog.publish("r1", "合成审核记录", List.of(entry("1", "模拟规则一", null)));
        assertEquals(1, catalog.bind(scope("1"), List.of("refund")).size());
        assertDoesNotThrow(() -> catalog.permit(scope("1"), "before"));
        catalog.revoke("refund", "1", "测试事实已失效");
        catalog.publish("r2", "合成审核记录", List.of(entry("2", "模拟规则二", null)));
        catalog.activate("r1");
        assertTrue(catalog.current().isEmpty());
        assertThrows(IllegalStateException.class, () -> catalog.permit(scope("1"), "after"));
    }
    @Test void expired_rule_cannot_be_bound_and_identity_cannot_be_borrowed() {
        var catalog = catalog();
        catalog.publish("r1", "合成审核记录", List.of(entry("1", "有效规则", null),
                new KnowledgeCatalog.Entry("expired", "1", "过期规则", "旧问题", "旧内容", Instant.EPOCH,
                        Instant.parse("2020-01-01T00:00:00Z"), List.of("旧"))));
        assertEquals(List.of("refund"), catalog.current().stream().map(KnowledgeCatalog.Entry::id).toList());
        assertThrows(IllegalStateException.class, () -> catalog.bind(scope("1"), List.of("expired")));
        catalog.bind(scope("1"), List.of("refund"));
        assertDoesNotThrow(() -> catalog.permit(scope("1"), "owned"));
        assertThrows(IllegalStateException.class, () -> catalog.permit(scope("2"), "borrowed"));
    }
    @Test void changing_same_version_is_rejected_without_switching_active_release() {
        var catalog = catalog();
        catalog.publish("r1", "合成审核记录", List.of(entry("1", "原审核正文", null)));
        assertEquals(1, catalog.current().size());
        assertThrows(IllegalArgumentException.class, () -> catalog.publish("r2", "合成审核记录",
                List.of(entry("1", "偷偷替换正文", null))));
        assertEquals("原审核正文", catalog.current().get(0).body());
    }
    @Test void reviewed_terms_are_configurable_and_multiple_sources_are_not_truncated() {
        // 新知识ID只靠审核文件词项即可被查询，不得依赖写死的八主题ID或只保留首项。
        var catalog = catalog();
        var rows = java.util.stream.IntStream.range(0, 7).mapToObj(i -> new KnowledgeCatalog.Entry("new-"+i, "1",
                "新增合成主题"+i, "独立问题"+i, "完整条件与否定"+i, Instant.EPOCH, null, List.of("新增问法"))).toList();
        catalog.publish("dynamic", "合成审核记录", rows);
        var answers = catalog.lookup(scope("1"), "解释新增问法", null);
        assertEquals(7, answers.size(), "不能把知识截成五条或只按旧ID匹配");
        assertEquals(7, catalog.permit(scope("1"), "all-sources").answers().size());
        assertTrue(answers.stream().allMatch(row -> row.keySet().equals(java.util.Set.of("id", "question", "answer"))));
    }
    @Test void unknown_domains_and_empty_question_do_not_become_successful_knowledge() {
        var catalog = catalog();
        catalog.publish("r1", "合成审核记录", List.of(entry("1", "模拟退款条件", null)));
        assertEquals(1, catalog.lookup(scope("1"), "平台退款条件", null).size());
        var other = new KnowledgeCatalog.Scope("other:faq:1", "1", "session", "other", "faq");
        assertTrue(catalog.lookup(other, "企业所得税退款流程", null).isEmpty());
        assertThrows(IllegalStateException.class, () -> catalog.permit(other, "no-source"));
        assertTrue(catalog.lookup(other, "", null).isEmpty());
    }
    @Test void help_page_intersection_and_revocation_use_authoritative_catalog() {
        var catalog = catalog();
        catalog.publish("r1", "合成审核记录", List.of(entry("1", "不承诺资金到账。", null)));
        assertEquals(1, catalog.publicRows("到账", "refund").size());
        assertTrue(catalog.publicRows("到账", "not-a-category").isEmpty());
        assertTrue(catalog.publicRows("不存在关键词", "refund").isEmpty());
        catalog.revoke("refund", "1", "资料失效");
        assertTrue(catalog.publicRows(null, null).isEmpty());
    }
    @Test void initial_bundle_must_not_overwrite_a_later_release_on_restart() {
        // 多次启动只允许第一次导入；新版业务事实或撤回不能被随包旧文件覆盖。
        var catalog = catalog();
        assertTrue(catalog.initialize("initial", "合成审核记录", List.of(entry("1", "初始正文", null))));
        catalog.publish("later", "合成审核记录", List.of(entry("2", "后续正文", null)));
        assertFalse(catalog.initialize("initial", "合成审核记录", List.of(entry("1", "初始正文", null))));
        assertEquals("后续正文", catalog.current().get(0).body());
        catalog.revoke("refund", "2", "后续正文撤回");
        assertFalse(catalog.initialize("initial", "合成审核记录", List.of(entry("1", "初始正文", null))));
        assertTrue(catalog.current().isEmpty());
    }
}
