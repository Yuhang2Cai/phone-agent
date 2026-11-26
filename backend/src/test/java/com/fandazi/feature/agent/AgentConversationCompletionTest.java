// 终态短事务验收：展示登记、待确认摘要和任务状态必须同时提交或回滚，不跨越模型和工具调用。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import static org.assertj.core.api.Assertions.*;

/** 真实JPA事务统计仅证明原子边界；实际MySQL性能须另跑固定负载，不从注解推定收益。 */
@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
class AgentConversationCompletionTest {
    @Autowired ApplicationContext application;
    @Autowired AgentConversationSourceService sources;
    @Autowired AgentSessionService sessions;
    @Autowired AgentPresentationRepository presentations;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;

    /** 稳定断言检查新边界存在；反射避免缺类的编译错误被算成指定红测。 */
    private Object completion() {
        assertThat(application.containsBean("agentConversationCompletionService"))
            .as("缺少展示登记与任务终态的单事务边界").isTrue();
        return application.getBean("agentConversationCompletionService");
    }

    /** 只传可信出口结果，返回值仍是原公开DTO；调用方不提供身份授权或确认凭证。 */
    private AgentResult complete(Object service,Long owner,String session,String run,String source,AgentResult result) throws Exception {
        Method method=service.getClass().getMethod("complete",Long.class,String.class,String.class,String.class,AgentResult.class);
        return (AgentResult)method.invoke(service,owner,session,run,source,result);
    }

    /** 动态合成身份避免测试间互相覆盖会话或列表，没有真实用户和明文密钥。 */
    private Long owner() {
        String unique=UUID.randomUUID().toString();
        return users.saveAndFlush(new UserEntity("completion-"+unique,unique+"@example.invalid","synthetic-hash")).getId();
    }

    @Test void presentationConfirmationAndRunStatusCommitOnce() throws Exception {
        Object service=completion();Long owner=owner();String session="completion-"+UUID.randomUUID();
        var started=sources.beginInput(owner,session,"合成购买准备","completion-key",AgentMode.CONFIRM,"trace");
        String source=(String)started.source().get("sourceMessageId");
        var summary=Map.<String,Object>of("action","create_group_buy_order","resourceId","synthetic-deal","quantity",1);
        var result=new AgentResult(AgentResult.Status.AWAITING_CONFIRMATION,"请确认","confirm",summary,List.of(),
            List.of(Map.of("type","store","resourceId","synthetic-store")),null,true);
        var manager=(ConfigurableTransactionManager)transactions;
        var original=List.copyOf(manager.getTransactionExecutionListeners());
        var commits=new java.util.concurrent.atomic.AtomicInteger();
        manager.addListener(new TransactionExecutionListener() {
            /** 只统计同步调用内实际成功提交，排除测试预置及后续读取。 */
            @Override public void afterCommit(TransactionExecution transaction,Throwable failure) {if(failure==null)commits.incrementAndGet();}
        });
        AgentResult actual;
        try {actual=complete(service,owner,session,started.runId(),source,result);}
        // 恢复可变列表，避免同Spring测试上下文的后续测试addListener遇到不可变集合。
        finally {manager.setTransactionExecutionListeners(new java.util.ArrayList<>(original));}
        assertThat(commits.get()).as("展示与确认及终态只能提交一次").isEqualTo(1);
        assertThat(actual.cards().get(0)).containsKey("presentation");
        var persisted=sessions.owned(owner,session);
        assertThat(persisted.getActiveRunStatus()).isEqualTo("waiting_confirmation");
        assertThat(persisted.getPendingConfirmation()).isEqualTo(summary);
        assertThat(presentations.findAllBySourceId(source)).hasSize(1);
    }

    @Test void staleConfirmationRollsBackRegisteredPresentationAndState() throws Exception {
        Object service=completion();Long owner=owner();String session="rollback-"+UUID.randomUUID();
        var started=sources.beginInput(owner,session,"合成输入","rollback-key",AgentMode.CONFIRM,"trace");
        String source=(String)started.source().get("sourceMessageId");
        var result=new AgentResult(AgentResult.Status.AWAITING_CONFIRMATION,"请确认","confirm",Map.of("action","create_group_buy_order"),List.of(),
            List.of(Map.of("type","store","resourceId","synthetic-store")),null,true);
        // 展示先登记，随后旧run确认校验必须失败并回滚，不能遗留可供点击的半份新列表。
        assertThatThrownBy(() -> complete(service,owner,session,"stale-run",source,result))
            .hasRootCauseInstanceOf(com.fandazi.feature.DomainException.class);
        assertThat(presentations.findAllBySourceId(source)).isEmpty();
        assertThat(sessions.owned(owner,session).getActiveRunStatus()).isEqualTo("running");
        assertThat(sessions.owned(owner,session).getPendingConfirmation()).isEmpty();
    }

    @Test void emptyResultInvalidatesPreviousListWithoutSkippingOwnership() throws Exception {
        Object service=completion();Long owner=owner();String session="empty-"+UUID.randomUUID();
        var started=sources.beginInput(owner,session,"合成输入","empty-key",AgentMode.CONFIRM,"trace");
        String source=(String)started.source().get("sourceMessageId");
        complete(service,owner,session,started.runId(),source,new AgentResult(AgentResult.Status.COMPLETED,"列表","none",Map.of(),List.of(),
            List.of(Map.of("type","store","resourceId","synthetic-store")),null,true));
        // 同来源重查后全部卡片消失，旧列表仍须失效，不能为了省查询直接跳过空列表登记。
        complete(service,owner,session,started.runId(),source,AgentResult.completed("已取消","cancelled",List.of()));
        assertThat(presentations.findAllBySourceId(source)).hasSize(1).allSatisfy(row -> assertThat(row.ids()).isEmpty());
        assertThatThrownBy(() -> complete(service,owner+100000,session,started.runId(),source,AgentResult.completed("结束","none",List.of())))
            .hasRootCauseInstanceOf(com.fandazi.feature.DomainException.class);
    }

    @Test void emptyCompletionUsesOneOwnedSourceLockAndOneStateUpdate() throws Exception {
        // 取消等无卡片终态仍须核验来源归属并检查旧展示，但不能在同一事务重复锁读会话。
        Object service=completion();Long owner=owner();String session="count-"+UUID.randomUUID();
        var started=sources.beginInput(owner,session,"合成取消","count-empty-key",AgentMode.CONFIRM,"trace-count");
        String source=(String)started.source().get("sourceMessageId");
        var statistics=entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();statistics.clear();
        complete(service,owner,session,started.runId(),source,AgentResult.completed("已取消","cancelled",List.of()));
        // 预算为：一次会话/来源联合归属锁读、一次该来源旧展示读取、一次会话终态更新。
        assertThat(statistics.getPrepareStatementCount()).as("无卡片出口不应重复读取同一会话和来源")
            .isLessThanOrEqualTo(3);
        assertThat(sessions.owned(owner,session).getActiveRunStatus()).isEqualTo("succeeded");
    }
}
