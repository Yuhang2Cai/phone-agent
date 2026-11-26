package com.fandazi.feature.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** AG08真MySQL并发反例：只允许显式本任务19336独立验收库，不连接默认或共享业务库。 */
@EnabledIfEnvironmentVariable(named="AG08_CATALOG_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:19336/ag08_knowledge_acceptance.*")
class KnowledgeCatalogMysqlTest {
    private static DriverManagerDataSource source;
    private static DataSourceTransactionManager manager;
    private static JdbcTemplate jdbc;
    private static KnowledgeCatalog catalog;

    @BeforeAll static void initialize() {
        // 凭据仅来自进程环境；沿用真实交付SQL，首次迁移失败不能换成测试专用表。
        source = new DriverManagerDataSource(System.getenv("AG08_CATALOG_MYSQL_URL"), System.getenv("AG08_CATALOG_MYSQL_USER"), System.getenv("AG08_CATALOG_MYSQL_PASSWORD"));
        manager = new DataSourceTransactionManager(source); jdbc = new JdbcTemplate(source);
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_head'", Integer.class);
        if (tables == 0) new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2026102901__knowledge_authority.sql")).execute(source);
        catalog = new KnowledgeCatalog(source, manager);
    }

    @Test void repeatable_read_snapshot_must_not_resurrect_revoked_source() {
        // 模拟外层领域事务先建立普通读快照，另一个实例撤回后，再进入知识许可。
        // 如果许可仍使用快照读，会读到过期前事实；仅锁head不足以证明所有来源读都是当前读。
        String unique = UUID.randomUUID().toString();
        var entry = new KnowledgeCatalog.Entry(unique, "1", "真实并发合成规则", "规则是什么？", "仅模拟交易。",
                Instant.EPOCH, null, List.of("规则"));
        var scope = new KnowledgeCatalog.Scope(unique + ":faq:1", "1", unique, unique, "faq");
        catalog.publish(unique, "自动化合成数据，非正式知识", List.of(entry));
        catalog.bind(scope, List.of(unique));
        assertDoesNotThrow(() -> catalog.permit(scope, "before-revocation"));
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_revocations", Integer.class);
            CompletableFuture.runAsync(() -> new KnowledgeCatalog(source, manager).revoke(unique, "1", "另一实例撤回")).join();
            assertThrows(IllegalStateException.class, () -> catalog.permit(scope, "after-revocation"),
                    "撤回已提交后，旧外层快照不能重新签发许可");
            // 预期拒绝会将外层事务标为只回滚；无需误把回滚传播作为测试错误。
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=? AND delivery_ref='after-revocation'", Integer.class, scope.executionKey()));
    }

    /** 每例使用新发布和新来源，原失败数据保持，不通过清库制造恢复假象。 */
    private KnowledgeCatalog.Scope fixture(Instant until) {
        String id = UUID.randomUUID().toString();
        catalog.publish(id, "并发验收合成内容", List.of(new KnowledgeCatalog.Entry(id, "1", "合成规则", "规则？",
                "不承诺真实到账。", Instant.EPOCH, until, List.of("规则"))));
        var scope = new KnowledgeCatalog.Scope(id+":faq:1", "1", id, id, "faq");
        catalog.bind(scope, List.of(id));
        return scope;
    }
    /** 有界同步只控制事务交错，不以线程调度快慢评估产品性能。 */
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "验收线程未到达指定事务点"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    @Test void revocation_commits_first_then_waiting_permit_is_rejected() throws Exception {
        var scope = fixture(null);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var revoke = CompletableFuture.runAsync(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            catalog.revoke(scope.runId(), "1", "先提交撤回"); locked.countDown(); await(release);
        }));
        await(locked);
        var pending = CompletableFuture.supplyAsync(() -> {
            try { catalog.permit(scope, "revocation-first"); return false; }
            catch (IllegalStateException expected) { return true; }
        });
        try { Thread.sleep(80); assertFalse(pending.isDone(), "许可绕过了未提交撤回事务的head锁"); }
        finally { release.countDown(); }
        revoke.get(5, TimeUnit.SECONDS);
        assertTrue(pending.get(5, TimeUnit.SECONDS));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=?", Integer.class, scope.executionKey()));
    }
    @Test void permit_commits_first_then_revocation_does_not_rewrite_past_fact() throws Exception {
        var scope = fixture(null);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delivery = CompletableFuture.supplyAsync(() -> new TransactionTemplate(manager).execute(status -> {
            var permit = catalog.permit(scope, "permit-first"); locked.countDown(); await(release); return permit;
        }));
        await(locked);
        var revoke = CompletableFuture.runAsync(() -> catalog.revoke(scope.runId(), "1", "许可之后撤回"));
        try { Thread.sleep(80); assertFalse(revoke.isDone(), "撤回绕过了未提交许可的head锁"); }
        finally { release.countDown(); }
        var permit = delivery.get(5, TimeUnit.SECONDS); revoke.get(5, TimeUnit.SECONDS);
        assertNotNull(permit);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=?", Integer.class, scope.executionKey()));
        assertThrows(IllegalStateException.class, () -> catalog.permit(scope, "late-redelivery"));
    }
    @Test void outer_transaction_rollback_does_not_leave_a_delivery_permit() {
        var scope = fixture(null);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            catalog.permit(scope, "event-save-will-fail"); status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_delivery_permits WHERE execution_key=?", Integer.class, scope.executionKey()));
    }
}
