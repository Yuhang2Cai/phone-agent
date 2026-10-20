// 来源关联验收：真实JPA事务验证用户历史与来源一起提交，失败不留下虚假的historyRecorded。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import static org.assertj.core.api.Assertions.*;

/** 使用真实Spring事务统计提交次数，H2行为结果不能冒充线上MySQL性能结论。 */
@SpringBootTest
class AgentLinkedHistoryTest {
    @Autowired AgentMessageWriter writer;
    @Autowired AgentConversationSourceService sources;
    @Autowired AgentSessionService sessions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;

    /** 先以稳定断言检查新边界，再反射调用，缺失实现不会被编译错误冒充有效红测。 */
    private Method linkedMethod() {
        Method found=java.util.Arrays.stream(AgentMessageWriter.class.getMethods())
            .filter(method -> method.getName().equals("writeLinkedSource")).findFirst().orElse(null);
        assertThat(found).as("缺少历史写入与来源关联的单事务边界").isNotNull();
        return found;
    }

    /** 新来源已经由同一网关签发；优化入口仍须带 epoch 做数据库条件归属核验。 */
    private Method verifiedLinkedMethod() {
        Method found=java.util.Arrays.stream(AgentMessageWriter.class.getMethods())
            .filter(method -> method.getName().equals("writeLinkedSourceVerified")).findFirst().orElse(null);
        assertThat(found).as("缺少已签发来源的条件关联边界").isNotNull();
        return found;
    }

    @Test void userHistoryAndSourceLinkCommitOnceAndRetryKeepsOneMessage() throws Exception {
        Method method=linkedMethod();
        String unique=UUID.randomUUID().toString();
        Long owner=users.saveAndFlush(new UserEntity("linked-"+unique,unique+"@example.invalid","synthetic-hash")).getId();
        String session="linked-"+unique;
        sessions.start(owner,session,AgentMode.CONFIRM,"trace-linked","2.1");
        var source=sources.issue(owner,session,"合成用户原文","linked-key");
        var manager=(ConfigurableTransactionManager)transactions;
        var original=java.util.List.copyOf(manager.getTransactionExecutionListeners());
        var commits=new java.util.concurrent.atomic.AtomicInteger();
        var listener=new TransactionExecutionListener() {
            /** 只统计本轮同步调用的实际提交，不能根据注解数量推断事务次数。 */
            @Override public void afterCommit(TransactionExecution transaction,Throwable failure) {
                if(failure==null)commits.incrementAndGet();
            }
        };
        manager.addListener(listener);
        try {
            method.invoke(writer,owner,session,"run-linked","trace-linked","合成用户原文","linked-key",source.get("sourceMessageId"));
        // 同一测试上下文还会安装其他事务监听器，恢复时保留可变集合防止跨测试状态污染。
        } finally { manager.setTransactionExecutionListeners(new java.util.ArrayList<>(original)); }
        assertThat(commits.get()).as("原文写入和来源关联只能提交一次").isEqualTo(1);
        assertThat(sources.issue(owner,session,"合成用户原文","linked-key")).containsEntry("historyRecorded",true);
        // 同展示键重试复用同条历史和原来源，不推进轮次、不重执业务。
        method.invoke(writer,owner,session,"run-linked","trace-retry","合成用户原文","linked-key",source.get("sourceMessageId"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE user_id=? AND session_id=? AND role='user'",Integer.class,owner,session)).isEqualTo(1);
    }

    @Test void linkedHistoryRollsBackWithItsSourceWhenTransactionFails() {
        // 写入和关联完成后注入事务失败，必须同时回滚，不能留下虚假的历史完整标记。
        String unique=UUID.randomUUID().toString(),session="rollback-"+unique;
        Long owner=users.saveAndFlush(new UserEntity("rollback-"+unique,unique+"@example.invalid","synthetic-hash")).getId();
        sessions.start(owner,session,AgentMode.CONFIRM,"trace-rollback","2.1");
        var source=sources.issue(owner,session,"合成回滚原文","rollback-key");
        var boundary=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(() -> boundary.execute(status -> {
            writer.writeLinkedSource(owner,session,"run-rollback","trace-rollback","合成回滚原文","rollback-key",(String)source.get("sourceMessageId"));
            throw new IllegalStateException("synthetic-after-link-failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("synthetic-after-link-failure");
        assertThat(sources.issue(owner,session,"合成回滚原文","rollback-key")).containsEntry("historyRecorded",false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE user_id=? AND session_id=?",Integer.class,owner,session)).isZero();
    }

    @Test void anotherSessionsSourceCannotBeLinkedOrWriteHistory() {
        // 同用户其他会话的来源也不是授权；拒绝时两个会话都不能产生新的历史或关联。
        String unique=UUID.randomUUID().toString(),first="first-"+unique,second="second-"+unique;
        Long owner=users.saveAndFlush(new UserEntity("scope-"+unique,unique+"@example.invalid","synthetic-hash")).getId();
        sessions.start(owner,first,AgentMode.CONFIRM,"trace-first","2.1");
        sessions.start(owner,second,AgentMode.CONFIRM,"trace-second","2.1");
        var source=sources.issue(owner,first,"合成来源原文","scope-key");
        assertThatThrownBy(() -> writer.writeLinkedSource(owner,second,"run-second","trace-second","合成来源原文","scope-key",(String)source.get("sourceMessageId")))
            .isInstanceOf(com.fandazi.feature.DomainException.class).hasMessage("消息来源不属于当前会话");
        assertThat(sources.issue(owner,first,"合成来源原文","scope-key")).containsEntry("historyRecorded",false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE user_id=?",Integer.class,owner)).isZero();
    }

    @Test void verifiedSourceLinksWithOneMessageReadInsertAndConditionalUpdate() throws Exception {
        // 来源已在紧邻的输入事务核验过；本事务仍用数据库条件UPDATE复核归属和当前epoch，不重复锁读实体。
        String unique=UUID.randomUUID().toString(),session="verified-"+unique;
        Long owner=users.saveAndFlush(new UserEntity("verified-"+unique,unique+"@example.invalid","synthetic-hash")).getId();
        sessions.start(owner,session,AgentMode.CONFIRM,"trace-verified","2.1");
        var source=sources.issue(owner,session,"合成已签发原文","verified-key");
        var statistics=entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();statistics.setStatisticsEnabled(true);statistics.clear();
        verifiedLinkedMethod().invoke(writer,owner,session,"run-verified","trace-verified","合成已签发原文","verified-key",
            source.get("sourceMessageId"),source.get("conversationEpoch"));
        // 正常路径只允许消息去重读、消息插入和来源条件更新；失败/重复分支可再读权威来源。
        assertThat(statistics.getPrepareStatementCount()).as("已签发来源关联不应重复查询会话和来源实体")
            .isLessThanOrEqualTo(3);
        assertThat(sources.issue(owner,session,"合成已签发原文","verified-key")).containsEntry("historyRecorded",true);
    }

    @Test void verifiedSourceRejectsAnotherSessionAndRollsBackHistory() {
        // 优化入口不能把“刚签发”当作授权；来源、会话和 epoch 任一不匹配都必须由数据库拒绝并回滚消息。
        String unique=UUID.randomUUID().toString(),first="verified-first-"+unique,second="verified-second-"+unique;
        Long owner=users.saveAndFlush(new UserEntity("verified-scope-"+unique,unique+"@example.invalid","synthetic-hash")).getId();
        sessions.start(owner,first,AgentMode.CONFIRM,"trace-first","2.1");
        sessions.start(owner,second,AgentMode.CONFIRM,"trace-second","2.1");
        var source=sources.issue(owner,first,"合成范围原文","verified-scope-key");
        assertThatThrownBy(() -> writer.writeLinkedSourceVerified(owner,second,"run-second","trace-second","合成范围原文","second-key",
            (String)source.get("sourceMessageId"),(String)source.get("conversationEpoch")))
            .isInstanceOf(com.fandazi.feature.DomainException.class)
            .hasMessage("消息来源不属于当前会话或已经失效");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE user_id=? AND session_id=?",Integer.class,owner,second)).isZero();
        assertThat(sources.issue(owner,first,"合成范围原文","verified-scope-key")).containsEntry("historyRecorded",false);
    }
}
