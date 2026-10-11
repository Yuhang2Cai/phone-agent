// 来源集成测试：使用真实 Spring/JPA 事务验证输入去重、冲突、顺序与历史关联。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 证明 JPA 事务路径行为，MySQL 生产并发必须在 CC-02/10 另行验证，不能混称。 */
@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
class AgentConversationSourceTest {
    @Autowired private AgentConversationSourceService sources;
    @Autowired private AgentSessionService sessions;
    @Autowired private UserRepository users;
    @Autowired private AgentHistoryRecordingService history;
    @Autowired private AgentEventService events;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagers;
    @Autowired private AgentConversationSourceRepository sourceRows;
    private Long userId;
    private String sessionId;

    /** 每例创建独立合成身份，禁止依赖前一例的顺序或已有生产记录。 */
    @BeforeEach void setUp() {
        String unique=UUID.randomUUID().toString();
        userId=users.saveAndFlush(new UserEntity("context-"+unique,"context-"+unique+"@example.invalid","synthetic-hash")).getId();
        sessionId="context-"+unique;
        sessions.start(userId,sessionId,AgentMode.CONFIRM,"trace-1","2.1");
    }

    @Test void identicalRetryReusesSourceAndDifferentBodyConflictsWithoutAdvancing() {
        // 重试身份独立于 trace；正文精确比对，拒绝冲突后下一真实输入仍为第二轮。
        var first=sources.issue(userId,sessionId,"第一句","display-1");
        var retry=sources.issue(userId,sessionId,"第一句","display-1");
        assertThat(retry).isEqualTo(first);
        assertThatThrownBy(() -> sources.issue(userId,sessionId,"第一句 ","display-1")).isInstanceOf(DomainException.class);
        assertThat(sources.issue(userId,sessionId,"第二句","display-2")).containsEntry("turnSequence",2L);
    }

    @Test void beginInputReusesLockedSessionAndInsertsFreshSource() {
        // 先建立真实等待停点，再只计下一次来源事务；不能把外层启动成本混入语句预算。
        var first=sources.beginInput(userId,sessionId,"购买套餐","count-first",AgentMode.CONFIRM,"count-trace-1");
        sessions.finishRun(userId,sessionId,first.runId(),AgentResult.Status.NEEDS_INPUT);
        var statistics=entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        var second=sources.beginInput(userId,sessionId,"取消","count-second",AgentMode.CONFIRM,"count-trace-2");
        // 来源创建仍须有真实行锁、正文去重、插入和顺序更新；省掉重复锁查询、提前刷新和新UUID查存在。
        assertThat(statistics.getPrepareStatementCount()).as("同事务来源分配不应重复读取会话或先查新UUID")
                .isLessThanOrEqualTo(5);
        // 语句数量下降后，等待任务、epoch和递增来源仍必须与之前的业务语义一致。
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.source()).containsEntry("turnSequence",2L).containsEntry("historyRecorded",false);
        assertThat(second.source().get("conversationEpoch")).isEqualTo(first.source().get("conversationEpoch"));
        assertThat(second.source().get("sourceMessageId")).isNotEqualTo(first.source().get("sourceMessageId"));
    }

    @Test void assignedSourceIdPersistsOnceAndReloadedSourceStaysExisting() {
        // 手工分配UUID仍是新来源；实际JPA不能先按ID读取不存在记录，也不能把加载后的来源再当新插入。
        var statistics=entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();statistics.clear();
        var value=sources.issue(userId,sessionId,"新来源","persist-source");
        assertThat(statistics.getPrepareStatementCount()).as("新UUID来源应直接插入，不附加按主键存在性读取")
                .isLessThanOrEqualTo(5);
        // 重新从库加载必须清除新对象标志，后续保存及同正文重试不能重新插入来源或推进顺序。
        var loaded=sourceRows.findById((String)value.get("sourceMessageId")).orElseThrow();
        assertThat(loaded).isInstanceOf(org.springframework.data.domain.Persistable.class);
        var state=(org.springframework.data.domain.Persistable<?>)loaded;
        assertThat(state.isNew()).isFalse();
        statistics.clear();sourceRows.saveAndFlush(loaded);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(sources.issue(userId,sessionId,"新来源","persist-source")).isEqualTo(value);
    }

    @Test void missingDisplayKeyDoesNotGuessRetryFromIdenticalText() {
        // 无来源键时相同文字也可能是两次真实输入，不能用正文 hash 去重。
        var first=sources.issue(userId,sessionId,"同一句",null);
        var second=sources.issue(userId,sessionId,"同一句",null);
        assertThat(second.get("sourceMessageId")).isNotEqualTo(first.get("sourceMessageId"));
        assertThat(second).containsEntry("turnSequence",2L);
    }

    @Test void ownershipAndEpochAreCheckedBeforeReadingOrLinking() {
        // 知道 sourceId 也不获得其他会话的访问权，错误关联不改变原来源。
        var source=sources.issue(userId,sessionId,"合成输入","owner-key");
        assertThatThrownBy(() -> sources.issue(userId+100000,sessionId,"合成输入","owner-key")).isInstanceOf(DomainException.class);
        String other="other-"+UUID.randomUUID();
        sessions.start(userId,other,AgentMode.CONFIRM,"trace-other","2.1");
        assertThatThrownBy(() -> sources.linkHistory(userId,other,(String)source.get("sourceMessageId"),"forged-message")).isInstanceOf(DomainException.class);
        assertThat(sources.issue(userId,sessionId,"合成输入","owner-key")).containsEntry("historyRecorded",false);
    }

    @Test void concurrentInputsAllocateOneSourcePerRetryAndMonotonicTurns() throws Exception {
        // 真实事务并发，不用进程内 synchronized 模拟数据库序列化。
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            var same=new ArrayList<Callable<Map<String,Object>>>();
            for(int i=0;i<8;i++)same.add(() -> sources.issue(userId,sessionId,"并发重试","same-key"));
            var identities=new HashSet<Object>();
            for(var result:pool.invokeAll(same)) {
                var value=result.get(10,TimeUnit.SECONDS);identities.add(value.get("sourceMessageId"));
                assertThat(value).containsEntry("turnSequence",1L);
            }
            assertThat(identities).hasSize(1);
            var different=new ArrayList<Callable<Map<String,Object>>>();
            for(int i=0;i<8;i++) {
                final int number=i;
                different.add(() -> sources.issue(userId,sessionId,"输入"+number,"different-"+number));
            }
            var turns=new TreeSet<Long>();
            for(var result:pool.invokeAll(different))turns.add((Long)result.get(10,TimeUnit.SECONDS).get("turnSequence"));
            assertThat(turns).containsExactly(2L,3L,4L,5L,6L,7L,8L,9L);
        } finally { pool.shutdownNow(); }
    }

    @Test void historyLinkReflectsActualMessagePersistence() {
        // 来源签发成功不等于消息已落库；实际历史 ID 成功关联后才能报告完整本轮。
        var source=sources.issue(userId,sessionId,"记录正文","history-key");
        String sourceId=(String)source.get("sourceMessageId");
        assertThat(sources.linkHistory(userId,sessionId,sourceId,null)).containsEntry("historyRecorded",false);
        String messageId=history.recordUserSource(userId,sessionId,"run-1","trace-1","记录正文","history-key");
        assertThat(messageId).isNotBlank();
        assertThat(sources.linkHistory(userId,sessionId,sourceId,messageId)).containsEntry("historyRecorded",true);
    }

    @Test void oldActiveRunFinishesAtOriginalVersionBeforeNewRunUpgrades() {
        // 模拟 B2 活动任务，先等待再切换部署；升级只在旧任务结束之后发生。
        String old="old-"+UUID.randomUUID();
        var initial=sessions.start(userId,old,AgentMode.CONFIRM,"old-trace","2.0");
        String run=initial.getActiveRunId();
        sessions.finishRun(userId,old,run,AgentResult.Status.NEEDS_INPUT);
        var resumed=sessions.start(userId,old,AgentMode.CONFIRM,"resume-trace","2.1");
        assertThat(resumed.getActiveRunId()).isEqualTo(run);
        assertThat(resumed.getContextProtocolVersion()).isEqualTo("2.0");
        sessions.finishRun(userId,old,run,AgentResult.Status.COMPLETED);
        assertThat(sessions.start(userId,old,AgentMode.CONFIRM,"new-trace","2.1").getContextProtocolVersion()).isEqualTo("2.1");
    }

    @Test void gatewayFreezesSourceAndHistoryBeforeCallingAgent() {
        // 外部 Agent 为替身，此例只验证真实网关签发及关联；不能计为真实业务工具证据。
        var client=mock(AgentServiceClient.class);
        when(client.run(any())).thenReturn(AgentResult.completed("合成回复","done",List.of()));
        var cards=mock(AgentCardEnrichmentService.class);
        when(cards.enrichSummary(anyLong(),anyString(),anyMap())).thenReturn(Map.of());
        var gateway=new AgentGatewayService(sessions,client,events,cards);
        gateway.attachMessages(history);gateway.attachSources(sources,"2.1");
        gateway.handle(userId,sessionId,"合成网关消息",AgentMode.CONFIRM,"gateway-key");
        gateway.handle(userId,sessionId,"合成网关消息",AgentMode.CONFIRM,"gateway-key");
        var capture=org.mockito.ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(client,times(2)).run(capture.capture());
        var first=capture.getAllValues().get(0);var second=capture.getAllValues().get(1);
        assertThat(first.transportVersion()).isEqualTo("2.1");
        assertThat(first.traceId()).isNotEqualTo(second.traceId());
        assertThat(first.context().get("conversationSource")).isEqualTo(second.context().get("conversationSource"));
        @SuppressWarnings("unchecked") var source=(Map<String,Object>)first.context().get("conversationSource");
        assertThat(source).containsEntry("historyRecorded",true).containsEntry("turnSequence",1L);
        // 正文冲突在 Agent 调用前抛出，不因复用展示键重新执行业务。
        String beforeTrace=sessions.owned(userId,sessionId).getTraceId();
        String beforeRun=sessions.owned(userId,sessionId).getActiveRunId();
        assertThatThrownBy(() -> gateway.handle(userId,sessionId,"换了正文",AgentMode.CONFIRM,"gateway-key"))
                .isInstanceOf(DomainException.class);
        assertThat(sessions.owned(userId,sessionId).getTraceId()).isEqualTo(beforeTrace);
        assertThat(sessions.owned(userId,sessionId).getActiveRunId()).isEqualTo(beforeRun);
        verify(client,times(2)).run(any());
    }
}
