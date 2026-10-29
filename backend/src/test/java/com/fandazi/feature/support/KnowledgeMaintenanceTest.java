package com.fandazi.feature.support;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 管理入口实际执行测试：验证正常启动审核包、显式无Web约束及撤回后的重启不复活。 */
class KnowledgeMaintenanceTest {
    private KnowledgeCatalog catalog() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2026102901__knowledge_authority.sql")).execute(source);
        return new KnowledgeCatalog(source, new DataSourceTransactionManager(source));
    }
    @Test void normal_start_imports_approved_bundle_and_restart_does_not_restore_revoked_rule() {
        var catalog = catalog();
        try (var context = new GenericApplicationContext()) {
            var maintenance = new KnowledgeMaintenance(catalog, new MockEnvironment(), context);
            maintenance.run(new DefaultApplicationArguments());
            assertEquals(8, catalog.current().size());
            assertTrue(catalog.current().stream().anyMatch(entry -> entry.body().contains("不承诺到账时间")));
            // 审核词项修订会依法提升条目版本；测试从实际活动发布取版本，仍验证同一条目撤回后重启不复活。
            var refund = catalog.current().stream().filter(entry -> entry.id().equals("refund-eligibility")).findFirst().orElseThrow();
            catalog.revoke(refund.id(), refund.version(), "重启撤回反例");
            maintenance.run(new DefaultApplicationArguments());
            assertEquals(7, catalog.current().size());
        }
    }
    @Test void management_commands_are_rejected_in_web_mode_before_any_mutation() {
        var catalog = catalog();
        try (var context = new GenericApplicationContext()) {
            var environment = new MockEnvironment().withProperty("knowledge.command", "activate").withProperty("knowledge.release", "unknown");
            var maintenance = new KnowledgeMaintenance(catalog, environment, context);
            assertThrows(IllegalArgumentException.class, () -> maintenance.run(new DefaultApplicationArguments()));
            assertTrue(catalog.current().isEmpty());
        }
    }
}
