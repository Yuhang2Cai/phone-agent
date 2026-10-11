// 任务迁移组件测试：Flyway真实建表后Hibernate校验，唯一键提供跨进程并发兜底。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** H2与MySQL执行同一迁移和约束测试，数据库产品在验收日志区分。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-migration;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789"})
class AgentTaskMigrationTest {
    @Autowired JdbcTemplate jdbc;@Autowired UserRepository users;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    private AgentTaskTestFixtures fixture;
    /** 每例新合成会话，不删除其他验收记录。 */
    @BeforeEach void setUp() { fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc); }
    @Test void productionMigrationAndHibernateValidationAreActuallyEnabled() {
        // 成功迁移记录与持久表均应存在，不能由create-drop掩盖迁移缺口。
        // 由Flyway读取真实历史，避免各数据库对引号/大小写不同导致测试自身语法错误。
        var applied=java.util.Arrays.stream(flyway.info().applied()).filter(m -> m.getVersion()!=null
                &&"2026101201".equals(m.getVersion().toString())).toList();
        assertEquals(1,applied.size());assertTrue(applied.get(0).getState().isApplied());
        assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_tasks",Long.class));
        assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_runs",Long.class));
    }
    @Test void databaseRejectsSecondActiveEvenWhenServiceIsBypassed() {
        // 直接SQL复制有效行也不能绕过唯一前台约束。
        String task=AgentTaskTestFixtures.id(fixture.selection());
        String sql="INSERT INTO agent_conversation_tasks (conversation_task_id,user_id,session_id,epoch,task_kind,intent,title,lifecycle,task_version,execution_generation,stop_kind,active_scope_key,created_at) SELECT ?,user_id,session_id,epoch,task_kind,intent,title,lifecycle,task_version,execution_generation,stop_kind,active_scope_key,created_at FROM agent_conversation_tasks WHERE conversation_task_id=?";
        assertThrows(DataIntegrityViolationException.class,() -> jdbc.update(sql,"synthetic-duplicate-"+task,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_tasks WHERE session_id=?",Integer.class,fixture.session));
    }
    @Test void databaseRejectsTwoInflightRunsForSameTask() {
        // 两个在途run不可同存；终态清空占位后新run才可建立。
        String task=AgentTaskTestFixtures.id(fixture.selection());var run=registry.beginRun(fixture.user,fixture.session,task);
        assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("INSERT INTO agent_task_runs (run_id,conversation_task_id,status,inflight_task_key,created_at) VALUES (?,?,'running',?,CURRENT_TIMESTAMP)","duplicate-"+run.id(),task,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_runs WHERE conversation_task_id=?",Integer.class,task));
    }
}
