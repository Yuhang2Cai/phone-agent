// AG06可用性收尾：独立核验被旧语句数量断言挡住的来源与历史业务结果，原性能断言保持失败记录。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import static org.junit.jupiter.api.Assertions.*;

/** 固定两条真实Spring/JPA正确性场景；不评SQL数量、延迟或容量，不调用Agent/模型。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag06-source-correctness;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","seed.enabled=false"})
class AgentSessionSourceCorrectnessTest {
    @Autowired AgentSessionService sessions;
    @Autowired AgentConversationSourceService sources;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    /** 等待任务的后续输入保持原run/epoch、分配新来源；同键重试不重复推进来源序号。 */
    @Test void waitingInputAndRetryPreserveRunEpochAndSourceSequence() {
        long userId=user();String sessionId="uc-source-"+UUID.randomUUID();
        sessions.start(userId,sessionId,AgentMode.CONFIRM,"initial","2.1");
        var first=sources.beginInput(userId,sessionId,"购买套餐","first",AgentMode.CONFIRM,"first-trace");
        sessions.finishRun(userId,sessionId,first.runId(),AgentResult.Status.NEEDS_INPUT);
        // 新输入是新的来源，但不能把等待任务转换为另一个run或会话epoch。
        var second=sources.beginInput(userId,sessionId,"取消","second",AgentMode.CONFIRM,"second-trace");
        assertEquals(first.runId(),second.runId());
        assertEquals(2L,second.source().get("turnSequence"));
        assertEquals(false,second.source().get("historyRecorded"));
        assertEquals(first.source().get("conversationEpoch"),second.source().get("conversationEpoch"));
        assertNotEquals(first.source().get("sourceMessageId"),second.source().get("sourceMessageId"));
        // 重试身份使用原客户端键；这里只核来源防重，不把旧2.1的重新受理误写为2.2轮次幂等。
        var retry=sources.beginInput(userId,sessionId,"取消","second",AgentMode.CONFIRM,"retry-trace");
        assertEquals(second.source(),retry.source());
    }

    /** 来源和用户历史在单事务中提交，已记录同键再次进入仍只有一条用户历史。 */
    @Test void recordedInputCommitsOnceAndRetryDoesNotDuplicateHistory() {
        long userId=user();String sessionId="uc-history-"+UUID.randomUUID();
        sessions.start(userId,sessionId,AgentMode.CONFIRM,"initial","2.1");
        var manager=(ConfigurableTransactionManager)transactions;
        var original=java.util.List.copyOf(manager.getTransactionExecutionListeners());
        var commits=new AtomicInteger();
        manager.addListener(new TransactionExecutionListener(){
            /** 只观察本轮同步生产入口的成功提交，不把回读和后续重试纳入一次提交分母。 */
            @Override public void afterCommit(TransactionExecution transaction,Throwable failure){if(failure==null)commits.incrementAndGet();}
        });
        AgentConversationSourceService.StartedInput first;
        try {
            first=sources.beginInputRecorded(userId,sessionId,"合成单事务输入","record-key",AgentMode.CONFIRM,"first",Map.of());
        } finally {
            // 即使断言或生产调用失败也恢复本测试上下文监听器，避免污染其他用例。
            manager.setTransactionExecutionListeners(new java.util.ArrayList<>(original));
        }
        assertEquals(1,commits.get());
        assertEquals(true,first.source().get("historyRecorded"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE user_id=? AND session_id=? AND role='user'",Integer.class,userId,sessionId));
        var retry=sources.beginInputRecorded(userId,sessionId,"合成单事务输入","record-key",AgentMode.CONFIRM,"retry",Map.of());
        assertEquals(first.source(),retry.source());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE user_id=? AND session_id=? AND role='user'",Integer.class,userId,sessionId));
    }

    /** 每例独立合成用户，避免固定身份与其他场景碰撞。 */
    private long user(){String id=UUID.randomUUID().toString();return users.saveAndFlush(new UserEntity("uc-source-"+id,id+"@example.invalid","synthetic-hash")).getId();}
}
