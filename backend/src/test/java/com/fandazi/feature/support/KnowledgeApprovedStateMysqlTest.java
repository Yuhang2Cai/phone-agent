package com.fandazi.feature.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** 八条用户认可正文逐条验证有效、撤回、回滚及过期；真实MySQL独立验收库，不改服务发布。 */
@EnabledIfEnvironmentVariable(named="AG08_CATALOG_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:19336/ag08_knowledge_acceptance.*")
class KnowledgeApprovedStateMysqlTest {
    private static KnowledgeCatalog catalog;
    private static JdbcTemplate jdbc;

    /** 验收前置沿用交付迁移和显式私有凭据，缺表时初始化，不清空任何历史失败数据。 */
    @BeforeAll static void initialize() {
        var source=new DriverManagerDataSource(System.getenv("AG08_CATALOG_MYSQL_URL"),
            System.getenv("AG08_CATALOG_MYSQL_USER"),System.getenv("AG08_CATALOG_MYSQL_PASSWORD"));
        jdbc=new JdbcTemplate(source);
        var tables=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_head'",Integer.class);
        if(tables==0)new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2026102901__knowledge_authority.sql")).execute(source);
        catalog=new KnowledgeCatalog(source,new DataSourceTransactionManager(source));
    }

    /** 读取已冻结审核原文；每个测试展示原主题ID，防止只测一条就宣称覆盖全部关键规则。 */
    static Stream<KnowledgeCatalog.Entry> approved() throws Exception {
        try(var input=new ClassPathResource("knowledge/reviewed-r1.json").getInputStream()) {
            var rows=new ObjectMapper().readTree(input).get("entries");
            assertEquals(8,rows.size(),"审核来源分母变化须重新冻结");
            var values=new java.util.ArrayList<KnowledgeCatalog.Entry>();
            for(var row:rows)values.add(new KnowledgeCatalog.Entry(row.get("id").asText(),row.get("version").asText(),
                row.get("title").asText(),row.get("question").asText(),row.get("body").asText(),Instant.EPOCH,null,List.of("隔离状态检查")));
            return values.stream();
        }
    }

    /** 正文保持原字节，只把来源身份和有效期换成私有合成状态，不冒充实际业务规则到期。 */
    private KnowledgeCatalog.Entry copy(KnowledgeCatalog.Entry original,String id,Instant until) {
        return new KnowledgeCatalog.Entry(id,original.version(),original.title(),original.question(),original.body(),
            Instant.EPOCH,until,original.terms());
    }

    @ParameterizedTest(name="八主题状态：{index} {0}") @MethodSource("approved")
    void each_approved_rule_rejects_revocation_rollback_expiry_and_foreign_scope(KnowledgeCatalog.Entry original) {
        // 有效态先取得真实许可和完整原文；若一开始全部拒绝，不能冒充后续撤销安全通过。
        String unique=original.id()+"-"+UUID.randomUUID();
        var scope=new KnowledgeCatalog.Scope(unique+":faq:1","synthetic-owner",unique,unique,"faq");
        catalog.publish(unique,"用户认可正文的隔离状态验证",List.of(copy(original,unique,null)));
        var bound=catalog.bind(scope,List.of(unique));assertEquals(1,bound.size());
        assertTrue(bound.get(0).get("answer").contains(original.body()));
        assertEquals(bound,catalog.permit(scope,"before").answers());
        var foreign=new KnowledgeCatalog.Scope(scope.executionKey(),"another-user",unique,unique,"faq");
        assertThrows(IllegalStateException.class,()->catalog.permit(foreign,"foreign"));
        // 撤销提交后即便重新激活同一发布也不能复活；旧绑定不能再次签发交付许可。
        catalog.revoke(unique,original.version(),"合成撤回场景");catalog.activate(unique);
        assertTrue(catalog.current().isEmpty());
        assertThrows(IllegalStateException.class,()->catalog.permit(scope,"after-revocation"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=? AND delivery_ref='after-revocation'",Integer.class,scope.executionKey()));
        // 已过期来源是独立来源版本，不更改旧正文或删除失败数据；绑定和公开读取同时拒绝。
        String expired=unique+"-expired";
        // 目录协议把有效期冻结到毫秒；合成时钟也必须按同一精度构造，不能让纳秒夹具误报业务失败。
        Instant expiredAt=Instant.ofEpochMilli(Instant.now().minusSeconds(1).toEpochMilli());
        catalog.publish(expired,"用户认可正文的合成过期状态",List.of(copy(original,expired,expiredAt)));
        var expiredScope=new KnowledgeCatalog.Scope(expired+":faq:1","synthetic-owner",expired,expired,"faq");
        assertTrue(catalog.current().isEmpty());assertTrue(catalog.publicRows(null,null).isEmpty());
        assertThrows(IllegalStateException.class,()->catalog.bind(expiredScope,List.of(expired)));
    }
}
