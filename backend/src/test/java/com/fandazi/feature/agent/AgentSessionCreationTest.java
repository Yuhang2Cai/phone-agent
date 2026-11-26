// AG06-B01会话创建回归：验证生产事务不先锁不存在行，并保持归属、协议及回滚语义。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** H2只用于低成本语义与SQL顺序检查；MySQL RR并发验证另行执行，不用本组代替。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag06-session-create;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","seed.enabled=false"})
@Import(AgentSessionCreationTest.SqlObservation.class)
class AgentSessionCreationTest {
    @Autowired AgentSessionService sessions;
    @Autowired AgentConversationSourceService sources;
    @Autowired AgentSessionRepository repository;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    // 只记录当前测试线程的SQL结构，不持有参数、身份凭证或其他测试的事件。
    private static final ThreadLocal<List<String>> SQL=ThreadLocal.withInitial(ArrayList::new);

    /** 观察真实Hibernate执行顺序，确保首次创建不取得不存在行的FOR UPDATE间隙锁。 */
    @Test void taskCreationInsertsBeforeLockingMissingSession() {
        var user=user();String key=key();SQL.get().clear();
        try {sessions.startTaskSession(user.getId(),key,"task-trace");assertCreationOrder();}
        finally {SQL.remove();}
    }
    /** 旧入口同样必须避免先锁缺失行，不能只修2.2而留下2.0/2.1隐患。 */
    @Test void legacyCreationInsertsBeforeLockingMissingSession() {
        var user=user();String key=key();SQL.get().clear();
        try {sessions.start(user.getId(),key,AgentMode.CONFIRM,"legacy-trace","2.1");assertCreationOrder();}
        finally {SQL.remove();}
    }
    /** 来源外层事务也必须沿安全创建路径，避免服务内部已修但调用者仍先取间隙锁。 */
    @Test void sourceCreationInsertsBeforeLockingMissingSession() {
        var user=user();String key=key();SQL.get().clear();
        try {sources.beginInput(user.getId(),key,"查看订单","source-key",AgentMode.CONFIRM,"source-trace");assertCreationOrder();}
        finally {SQL.remove();}
    }
    /** 同一会话的外来用户不能改变归属、epoch或已登记的trace，且重复创建只有一行。 */
    @Test void repeatedTaskCreationPreservesOwnerAndEpoch() {
        var owner=user();var outsider=user();String key=key();
        var first=sessions.startTaskSession(owner.getId(),key,"first");
        var again=sessions.startTaskSession(owner.getId(),key,"second");
        assertEquals(first.getId(),again.getId());assertEquals(first.conversationEpoch(),again.conversationEpoch());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->sessions.startTaskSession(outsider.getId(),key,"foreign")).getStatusCode().value());
        var stored=repository.findBySessionId(key).orElseThrow();
        assertEquals(owner.getId(),stored.ownerId());assertEquals("first",stored.getTraceId());
        assertEquals(1,jdbc.queryForObject("select count(*) from agent_sessions where session_id=?",Integer.class,key));
    }
    /** 创建加入外层事务；后续失败必须撤销整行，不能使用独立提交的占位会话。 */
    @Test void outerFailureRollsBackNewSession() {
        var owner=user();String key=key();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            sessions.startTaskSession(owner.getId(),key,"rollback");throw new IllegalStateException("指定回滚");}));
        assertTrue(repository.findBySessionId(key).isEmpty(),"失败事务不能残留占位会话");
    }
    /** 无效用户在两种首次创建入口均被明确拒绝，不能留下占位行或漏出数据库外键错误。 */
    @Test void nonexistentUserCannotLeavePlaceholderInEitherProtocol() {
        long missing=Long.MAX_VALUE;String taskKey=key(),legacyKey=key();
        assertFalse(users.existsById(missing),"本例用户必须确实不存在");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->sessions.startTaskSession(missing,taskKey,"invalid-task")).getStatusCode().value());
        assertThrows(com.fandazi.feature.DomainException.class,()->sessions.start(missing,legacyKey,AgentMode.CONFIRM,"invalid-legacy","2.1"));
        assertTrue(repository.findBySessionId(taskKey).isEmpty());
        assertTrue(repository.findBySessionId(legacyKey).isEmpty());
    }
    /** 新建机制不允许把旧等待确认任务直接升级2.2，必须保持原run和版本。 */
    @Test void waitingLegacyRunCannotBeSilentlyUpgraded() {
        var owner=user();String key=key();var first=sessions.start(owner.getId(),key,AgentMode.CONFIRM,"old","2.1");
        new TransactionTemplate(transactions).execute(status->{var row=repository.findBySessionIdForUpdate(key).orElseThrow();row.bindTaskRun(first.getActiveRunId(),"waiting_confirmation");return null;});
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.startTaskSession(owner.getId(),key,"new")).getStatusCode().value());
        var stored=repository.findBySessionId(key).orElseThrow();assertEquals("2.1",stored.getContextProtocolVersion());
        assertEquals(first.getActiveRunId(),stored.getActiveRunId());assertEquals("waiting_confirmation",stored.getActiveRunStatus());
    }
    /** 返回独立合成用户，避免固定ID碰撞污染其他测试。 */
    private UserEntity user(){String id=UUID.randomUUID().toString();return users.saveAndFlush(new UserEntity("uc-"+id,id+"@example.invalid","synthetic-hash"));}
    /** 新的会话键使每次验证确实走首次创建分支。 */
    private String key(){return "uc-"+UUID.randomUUID();}
    /** 只限定会话表的创建/行锁先后，不对普通查询或驱动内部SQL数量作机械约束。 */
    private void assertCreationOrder(){
        int insert=-1,lock=-1;var statements=SQL.get();
        for(int i=0;i<statements.size();i++){String sql=statements.get(i).toLowerCase();
            if(sql.contains("insert into agent_sessions")&&insert<0)insert=i;
            if(sql.contains("agent_sessions")&&sql.contains("for update")&&lock<0)lock=i;
        }
        assertTrue(insert>=0,"必须执行真实创建SQL");
        assertTrue(lock<0||insert<lock,"首次创建不能在INSERT之前锁定不存在的会话");
    }
    /** 观察器只用于本独立测试上下文，不修改生产SQL或事务隔离级别。 */
    @TestConfiguration static class SqlObservation {
        /** 将SQL结构加入当前线程列表后原样返回，不改变执行结果。 */
        @Bean HibernatePropertiesCustomizer captureSql(){return properties->properties.put("hibernate.session_factory.statement_inspector",(StatementInspector)sql->{SQL.get().add(sql);return sql;});}
    }
}
