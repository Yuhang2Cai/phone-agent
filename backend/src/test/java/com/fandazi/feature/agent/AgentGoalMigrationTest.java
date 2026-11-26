// GR-02迁移测试：真实Flyway历史上追加目标表与兼容默认值，旧2.2任务不得被改写为2.3。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import javax.sql.DataSource;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 启动时由Hibernate validate核对实体；本类继续检查数据库列、索引和旧行默认值。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG04_TEST_DB_URL:jdbc:h2:mem:ag04-migration;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG04_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG04_TEST_DB_USER:sa}","spring.datasource.password=${AG04_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.internal-token=synthetic-test-only-token"})
class AgentGoalMigrationTest {
    @Autowired JdbcTemplate jdbc;@Autowired UserRepository users;@Autowired AgentSessionService sessions;
    @Autowired AgentTaskRegistryService registry;@Autowired DataSource dataSource;

    @Test void migrationCreatesGoalTablesAndKeepsLegacyDefaults() {
        // 先真实迁移到AG03末版并插入旧行，再追加AG04迁移；不能用新实体创建的数据冒充旧库升级证据。
        Flyway old=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false)
                .target("2026101206").load();old.clean();old.migrate();
        jdbc.update("INSERT INTO app_users(username,email,password_hash) VALUES(?,?,?)","legacy-goal","legacy-goal@example.invalid","synthetic-hash");
        Long user=jdbc.queryForObject("SELECT id FROM app_users WHERE username='legacy-goal'",Long.class);
        jdbc.update("INSERT INTO agent_sessions(user_id,session_id,mode,trace_id,confirmed,confirmation_consumed,created_order_settled,conversation_epoch,context_protocol_version) VALUES(?,?,?,?,?,?,?,?,?)",
                user,"legacy-goal-session","AGENT","legacy-trace",false,false,false,"legacy-epoch","2.2");
        jdbc.update("INSERT INTO agent_conversation_tasks(conversation_task_id,user_id,session_id,epoch,task_kind,intent,title,lifecycle,task_version,execution_generation,stop_kind,active_scope_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                "legacy-goal-task",user,"legacy-goal-session","legacy-epoch","selection","deal_recommendation","旧2.2任务","active",1,1,"running","legacy-scope");
        Flyway latest=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load();latest.migrate();

        // 新列默认值必须保留旧任务协议和三版本，不能因部署把已有任务自动升级为2.3。
        Map<String,Object> row=jdbc.queryForMap("SELECT protocol_version AS PROTOCOL_VERSION,goal_revision AS GOAL_REVISION,plan_revision AS PLAN_REVISION,effect_version AS EFFECT_VERSION FROM agent_conversation_tasks WHERE conversation_task_id=?","legacy-goal-task");
        assertEquals("2.2",row.get("PROTOCOL_VERSION"));
        assertEquals(1L,((Number)row.get("GOAL_REVISION")).longValue());
        assertEquals(1L,((Number)row.get("PLAN_REVISION")).longValue());
        assertEquals(0L,((Number)row.get("EFFECT_VERSION")).longValue());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) IN ('agent_task_goals','agent_goal_recoveries','agent_goal_effects')",Integer.class));
    }
}
