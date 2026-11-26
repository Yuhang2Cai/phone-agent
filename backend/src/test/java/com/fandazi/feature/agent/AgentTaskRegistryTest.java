// 任务目录组件测试：真实Spring/JPA事务覆盖S07/S08/S09/S21，H2和真实MySQL分开记录。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 首次红测已从原会话入口触发409指定断言；实现后直接校验受控目录入口。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-registry;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789",
    "agent.internal-token=synthetic-test-only-token"})
class AgentTaskRegistryTest {
    @Autowired AgentTaskRegistryService registry;@Autowired AgentSessionService sessions;
    @Autowired UserRepository users;@Autowired AgentTaskRunRepository runs;@Autowired JdbcTemplate jdbc;
    private AgentTaskTestFixtures fixture;
    /** 每例使用新合成账号/会话，MySQL重复运行不覆盖先前验收事实。 */
    @BeforeEach void setUp() { fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc); }

    @Test void secondActiveTaskMustBeRejected() {
        // 指定预期保持首次红测：第二次创建必须409，原目录逐字段不变。
        var first=fixture.selection();var before=fixture.directory();
        assertEquals(409,assertThrows(ResponseStatusException.class,fixture::selection).getStatusCode().value(),"第二个前台任务必须拒绝且不覆盖已有任务");
        assertEquals(before,fixture.directory());assertEquals(first,AgentTaskTestFixtures.current(fixture.directory()));
    }
    @Test void crossOwnerAndOldEpochCannotReadTasks() {
        // taskId不能替代scope；不存在、越权与旧epoch均404，不泄漏区别。
        String id=AgentTaskTestFixtures.id(fixture.selection());var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        assertEquals(404,assertThrows(ResponseStatusException.class,() -> registry.directory(other.user,fixture.session)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,() -> registry.task(fixture.user,fixture.session,id,"old-epoch")).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,() -> registry.task(other.user,other.session,id,other.epoch)).getStatusCode().value());
    }
    @Test void fivePausedRejectPauseAndInsertButAllowNetZeroSwap() {
        // 直接种行只建立暂停初始条件；实际控制交接在TL-03另验。
        var paused=new ArrayList<String>();for(int i=0;i<5;i++)paused.add(fixture.seedPaused());
        String active=AgentTaskTestFixtures.id(fixture.selection());var before=fixture.directory();
        for(String action:List.of("pause","insert"))assertEquals(409,assertThrows(ResponseStatusException.class,
            () -> registry.previewControl(fixture.user,fixture.session,action,active)).getStatusCode().value());
        assertEquals(5,registry.previewControl(fixture.user,fixture.session,"switch",paused.get(0)));
        assertEquals(5,registry.previewControl(fixture.user,fixture.session,"resume",paused.get(0)));assertEquals(before,fixture.directory());
    }
    @Test void terminalTasksDoNotConsumePausedCapacityAndDirectorySortingIsStable() {
        // 终态历史不计入五暂停；展示排序不能用于隐式恢复目标。
        for(int i=0;i<8;i++) {
            String task=AgentTaskTestFixtures.id(registry.create(fixture.user,fixture.session,"lookup","faq","合成常见问题"));
            var run=registry.beginRun(fixture.user,fixture.session,task);registry.recordRunStop(fixture.user,fixture.session,task,run.id(),"succeeded",null,null);
        }
        for(int i=0;i<5;i++)fixture.seedPaused();assertEquals(5,AgentTaskTestFixtures.paused(fixture.directory()).size());
        assertEquals(fixture.directory(),fixture.directory());fixture.selection();assertNotNull(fixture.directory().get("currentTask"));
    }
    @Test void recommendationFollowupCreatesNewRunAndOldRunStaysTerminal() {
        // S21：推荐run结束不等于任务结束；合法追问新建同task run，旧run不可重开。
        String task=AgentTaskTestFixtures.id(fixture.selection());var first=registry.beginRun(fixture.user,fixture.session,task);
        assertEquals(first.id(),registry.beginRun(fixture.user,fixture.session,task).id());
        var stopped=registry.recordRunStop(fixture.user,fixture.session,task,first.id(),"succeeded",null,null);
        assertEquals("active",stopped.get("lifecycle"));assertNull(stopped.get("activeRunId"));
        assertEquals("awaiting_followup",AgentTaskTestFixtures.stop(stopped).get("kind"));long generation=((Number)stopped.get("executionGeneration")).longValue();
        var second=registry.beginRun(fixture.user,fixture.session,task);assertNotEquals(first.id(),second.id());assertEquals(task,second.taskId());
        assertEquals("succeeded",runs.findById(first.id()).orElseThrow().status());assertEquals(2,runs.findByTaskId(task).size());
        assertEquals(generation+1,((Number)AgentTaskTestFixtures.current(fixture.directory()).get("executionGeneration")).longValue());
        var before=fixture.directory();registry.recordRunStop(fixture.user,fixture.session,task,first.id(),"succeeded",null,null);assertEquals(before,fixture.directory());
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> registry.recordRunStop(fixture.user,fixture.session,task,first.id(),"running",null,null)).getStatusCode().value());
    }
    @Test void lookupSuccessClearsFocusAndTerminalTaskCannotResume() {
        // 查询成功关闭task并清空焦点；重复终态补交不再推进版本。
        String task=AgentTaskTestFixtures.id(registry.create(fixture.user,fixture.session,"lookup","group_buy_order","合成订单查询"));
        var run=registry.beginRun(fixture.user,fixture.session,task);long before=((Number)fixture.directory().get("focusVersion")).longValue();
        var result=registry.recordRunStop(fixture.user,fixture.session,task,run.id(),"succeeded",null,null);
        assertEquals("completed",result.get("lifecycle"));assertNull(fixture.directory().get("currentTask"));
        assertEquals(before+1,((Number)fixture.directory().get("focusVersion")).longValue());
        var after=fixture.directory();registry.recordRunStop(fixture.user,fixture.session,task,run.id(),"succeeded",null,null);assertEquals(after,fixture.directory());
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> registry.beginRun(fixture.user,fixture.session,task)).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> registry.previewControl(fixture.user,fixture.session,"cancel",task)).getStatusCode().value());
    }
    @Test void waitingInputKeepsOriginalRunAndOnlyAdvancesTaskVersion() {
        // 业务停点不改变焦点/代次；本轮尚未引入任务调度暂停。
        String task=AgentTaskTestFixtures.id(fixture.selection());var run=registry.beginRun(fixture.user,fixture.session,task);
        var before=fixture.directory();var old=AgentTaskTestFixtures.current(before);
        var stop=registry.recordRunStop(fixture.user,fixture.session,task,run.id(),"waiting_input","dealId","synthetic-goal");
        assertEquals(before.get("focusVersion"),fixture.directory().get("focusVersion"));assertEquals(old.get("executionGeneration"),stop.get("executionGeneration"));
        assertEquals(((Number)old.get("taskVersion")).longValue()+1,((Number)stop.get("taskVersion")).longValue());
        assertEquals(run.id(),registry.beginRun(fixture.user,fixture.session,task).id());
    }
    @Test void concurrentCreateKeepsExactlyOneActiveTask() throws Exception {
        // 12个真实事务竞争同session，严格1成功/11冲突，不能覆盖前台。
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(12);
        try {
            var calls=new ArrayList<Future<Integer>>();for(int i=0;i<12;i++)calls.add(pool.submit(() -> {start.await();try {fixture.selection();return 201;}catch(ResponseStatusException error) {return error.getStatusCode().value();}}));
            start.countDown();var statuses=new ArrayList<Integer>();for(var call:calls)statuses.add(call.get(20,TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(n -> n==201).count());assertEquals(11,statuses.stream().filter(n -> n==409).count());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_tasks WHERE session_id=? AND lifecycle='active'",Integer.class,fixture.session));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentBeginKeepsExactlyOneInFlightRun() throws Exception {
        // 同任务并发跟进由会话锁与数据库唯一键共同保证只分配一个run。
        String task=AgentTaskTestFixtures.id(fixture.selection());var pool=Executors.newFixedThreadPool(8);
        try {
            var calls=new ArrayList<Future<String>>();for(int i=0;i<8;i++)calls.add(pool.submit(() -> registry.beginRun(fixture.user,fixture.session,task).id()));
            var ids=new HashSet<String>();for(var call:calls)ids.add(call.get(20,TimeUnit.SECONDS));assertEquals(1,ids.size());assertEquals(1,runs.findByTaskId(task).size());
        } finally {pool.shutdownNow();}
    }
    @Test void oldEntryCannotDowngradeTaskSessionOrMigrateLegacyRun() {
        // 部署切换不迁移旧run，旧消息入口也不能覆写2.2目录。
        fixture.selection();var before=fixture.directory();assertEquals(409,assertThrows(ResponseStatusException.class,
            () -> sessions.start(fixture.user,fixture.session,AgentMode.CONFIRM,"legacy-trace","2.1")).getStatusCode().value());assertEquals(before,fixture.directory());
        String legacy="synthetic-legacy-"+UUID.randomUUID();sessions.start(fixture.user,legacy,AgentMode.CONFIRM,"legacy-trace","2.1");
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> sessions.startTaskSession(fixture.user,legacy,"new-trace")).getStatusCode().value());
    }
    @Test void invalidKindIntentAndCrossTaskRunDoNotChangeDirectory() {
        // 退款不可伪装为可跟进选择任务；其他task run不能修改当前停点。
        assertEquals(400,assertThrows(ResponseStatusException.class,() -> registry.create(fixture.user,fixture.session,"selection","refund_after_sale","错误类型")).getStatusCode().value());
        var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);String own=AgentTaskTestFixtures.id(fixture.selection()),foreign=AgentTaskTestFixtures.id(other.selection());
        registry.beginRun(fixture.user,fixture.session,own);var run=registry.beginRun(other.user,other.session,foreign);var before=fixture.directory();
        assertEquals(404,assertThrows(ResponseStatusException.class,() -> registry.recordRunStop(fixture.user,fixture.session,own,run.id(),"succeeded",null,null)).getStatusCode().value());assertEquals(before,fixture.directory());
    }
    @Test void terminalLegacyRunMayStartTaskProtocolWithoutReopeningOldRun() {
        // 规格允许旧run终态后按显式新入口启用2.2；不能让旧会话永远无法升级，也不能复活旧run。
        String legacy="synthetic-finished-"+UUID.randomUUID();
        var old=sessions.start(fixture.user,legacy,AgentMode.CONFIRM,"old-trace","2.1");
        sessions.finishRun(fixture.user,legacy,old.getActiveRunId(),AgentResult.Status.COMPLETED);
        assertDoesNotThrow(() -> sessions.startTaskSession(fixture.user,legacy,"new-trace"),"旧终态run后应允许显式新任务协议");
        assertNull(registry.directory(fixture.user,legacy).get("currentTask"));
        assertNull(sessions.owned(fixture.user,legacy).getActiveRunId());
    }
}
