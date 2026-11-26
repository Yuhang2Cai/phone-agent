// 任务授权隔离测试：实际领域报价/订单与凭证消费，覆盖正常确认、撤销、旧入口和购买/支付边界。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 新旧协议消费共用领域服务，任务凭证不能借页面或旧v1绕过栅栏。 */
@SpringBootTest(properties={"spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-grants;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=false"})
class AgentTaskGrantIsolationTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;
    @Autowired AgentTaskToolBridge tools;@Autowired AgentTaskFenceService fences;@Autowired JdbcTemplate jdbc;@Autowired StoreRepository stores;@Autowired DealPackageRepository deals;
    @Autowired ConfirmationGrantRepository grants;@Autowired ConfirmationGrantService confirmations;@Autowired AgentTaskControlService controls;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired AgentTaskReceiptService receipts;@Autowired com.fandazi.feature.grouporder.GroupBuyOrderRepository orders;
    @Autowired com.fandazi.feature.grouporder.GroupBuyOrderService groupOrders;
    AgentTaskToolTestFixtures fixture;
    /** 每例独立合成对象，不复用其他任务或用户的报价/授权。 */
    @BeforeEach void setup(){fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);}
    @Test void normalConfirmationCreatesOneOrderAndSeparatePaymentGrant(){
        // S22：正常确认不能被普通受理误撤销，重复工具接收仍只有一个订单和执行记录。
        var quote=fixture.quote(1);var grant=grants.findByGrantId((String)quote.get("confirmationGrantId")).orElseThrow();
        assertEquals(fixture.taskId,grant.getConversationTaskId());assertEquals(1L,grant.getExecutionGeneration());
        fixture.awaitAndConfirm(quote);var request=fixture.creation(quote);var order=tools.execute("create_group_buy_order",request);
        assertEquals(2,((Number)order.get("quantity")).intValue());assertEquals(2468,((Number)order.get("totalCents")).intValue());assertEquals("PENDING_PAYMENT",order.get("status"));
        assertEquals(AgentTaskFingerprint.of(order),AgentTaskFingerprint.of(tools.execute("create_group_buy_order",request)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
        var payment=grants.findByGrantId((String)order.get("paymentConfirmationGrantId")).orElseThrow();assertEquals("PAY_MOCK_ORDER",payment.getAction());
        assertEquals(fixture.taskId,payment.getConversationTaskId());assertFalse(payment.isConsumed());assertTrue(grants.findByGrantId(grant.getGrantId()).orElseThrow().isConsumed());
    }
    @Test void taskGrantCannotBeConsumedWithoutCurrentFence(){
        // S17：领域/页面的旧消费入口没有可信task上下文，必须拒绝新grant且不能吞掉它。
        var quote=fixture.quote(1);String grant=(String)quote.get("confirmationGrantId");
        assertEquals("FORBIDDEN",assertThrows(ToolBoundaryException.class,()->confirmations.consumeBound(fixture.scope.user,fixture.scope.session,grant,"CREATE_GROUP_BUY_ORDER",
            (String)quote.get("quoteId"),2468,(String)quote.get("confirmationRuleVersion"))).getCode());assertFalse(grants.findByGrantId(grant).orElseThrow().isConsumed());
    }
    @Test void expiredTaskGrantCannotMarkTurnConfirmed(){
        // AG05-F07：展示之后服务端授权到期时，确认入口必须在调用 Agent 和领域写入前以 EXPIRED 拒绝。
        var quote=fixture.quote(1);String grant=(String)quote.get("confirmationGrantId");
        var summary=Map.<String,Object>of("action","create_group_buy_order","resourceId",quote.get("quoteId"),"title","合成过期确认",
                "amountCents",quote.get("totalCents"),"ruleVersion",quote.get("confirmationRuleVersion"),"dealTitle","合成双份套餐","quantity",2);
        fixture.awaitConfirmation(summary);var displayed=sessions.owned(fixture.scope.user,fixture.scope.session).getPendingConfirmation();
        // 只改变服务端权威期限，保留客户端已展示摘要，复现“页面未变但授权刚刚过期”的真实时间竞态。
        assertEquals(1,jdbc.update("UPDATE confirmation_grants SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE grant_id=?",grant));
        fixture.admit("合成用户明确确认过期授权");
        assertEquals("EXPIRED",assertThrows(ToolBoundaryException.class,
                ()->fences.confirmCurrent(fixture.fence(),fixture.trace(),displayed)).getCode());
        assertFalse(grants.findByGrantId(grant).orElseThrow().isConsumed());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
    }
    @Test void pauseRevokesOnlyAffectedTaskGrant(){
        // S05/S15：预备暂停与代次推进同时撤销未消费grant，普通新准备只能使用新动作键。
        var quote=fixture.quote(1);var task=AgentTaskTestFixtures.current(fixture.scope.directory());
        controls.prepare(fixture.scope.user,fixture.scope.session,Map.of("requestId","pause-own-grant","action","pause","targetTaskId",fixture.taskId,
            "expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",fixture.scope.directory().get("focusVersion")));
        assertTrue(grants.findByGrantId((String)quote.get("confirmationGrantId")).orElseThrow().isRevoked());
        assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,()->fixture.quote(1)).getCode());
    }
    @Test void purchaseConfirmationCannotAuthorizePayment(){
        // S13：创建订单后购买确认已经消费；支付凭证独立签发，也不能在原确认turn自动支付。
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var order=tools.execute("create_group_buy_order",fixture.creation(quote));
        var request=fixture.request("pay",1,Map.of("orderId",order.get("orderId"),"confirmationGrantId",order.get("paymentConfirmationGrantId")));
        assertEquals("FORBIDDEN",assertThrows(ToolBoundaryException.class,()->tools.execute("pay_mock_order",request)).getCode());
        assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,order.get("orderId")));
    }
    /** 明确控制按钮输入冻结于竞争前；不能按竞态输出反过来修改预期版本。 */
    Map<String,Object> pauseRequest(String requestId){var task=AgentTaskTestFixtures.current(fixture.scope.directory());return Map.of("requestId",requestId,"action","pause","targetTaskId",fixture.taskId,
        "expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",fixture.scope.directory().get("focusVersion"));}
    /** 返回实际数据库物理连接身份，证明竞争双方使用不同连接，而非同线程/内存锁替身。 */
    long connectionId(){String product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
        return jdbc.queryForObject(product.contains("MySQL")?"SELECT CONNECTION_ID()":"SELECT SESSION_ID()",Long.class);}
    @Test void controlCommitFirstRejectsConcurrentOldWrite() throws Exception {
        // S05：控制先持session锁预备；另一真实连接持事务等待，控制提交后旧写CONFLICT且订单/执行均0。
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var request=fixture.creation(quote);var pause=pauseRequest("control-first");
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var started=new java.util.concurrent.CountDownLatch(1);var ids=new java.util.concurrent.atomic.AtomicLongArray(2);
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            tx.execute(status->{ids.set(0,connectionId());controls.prepare(fixture.scope.user,fixture.scope.session,pause);
                // 在根事务外捕获业务拒绝，避免内层已标rollback-only却被测试吞异常，误报UnexpectedRollback。
                future.set(pool.submit(()->{try{return tx.execute(inner->{ids.set(1,connectionId());started.countDown();tools.execute("create_group_buy_order",request);return "unexpected_write";});}
                    catch(ToolBoundaryException denied){return denied.getCode();}}));
                try{assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}return null;});
            assertEquals("CONFLICT",future.get().get(15,java.util.concurrent.TimeUnit.SECONDS));assertNotEquals(ids.get(0),ids.get(1));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions WHERE execution_key=?",Integer.class,request.idempotencyKey()));
            System.out.println("控制先提交锁竞争通过：控制连接="+ids.get(0)+"，业务连接="+ids.get(1));
        } finally {pool.shutdownNow();}
    }
    @Test void businessCommitFirstPersistsExactlyOneFactBeforeControl() throws Exception {
        // S05：业务先持相同session/task锁并写入；控制连接等待，业务提交后控制才推进代次，原事实可回查。
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var request=fixture.creation(quote);var pause=pauseRequest("business-first");
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var started=new java.util.concurrent.CountDownLatch(1);var ids=new java.util.concurrent.atomic.AtomicLongArray(2);
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Map<String,Object>>>();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            var order=tx.execute(status->{ids.set(0,connectionId());var result=tools.execute("create_group_buy_order",request);
                future.set(pool.submit(()->tx.execute(inner->{ids.set(1,connectionId());started.countDown();return controls.prepare(fixture.scope.user,fixture.scope.session,pause);})));
                try{assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}return result;});
            assertEquals("prepared",future.get().get(15,java.util.concurrent.TimeUnit.SECONDS).get("state"));assertNotEquals(ids.get(0),ids.get(1));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions WHERE execution_key=?",Integer.class,request.idempotencyKey()));
            assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,()->tools.execute("create_group_buy_order",request)).getCode());
            var fact=receipts.read(fixture.scope.user,fixture.scope.session,fixture.scope.epoch,fixture.taskId,request.runId(),(String)request.taskFence().get("turnId"),request.idempotencyKey());
            assertEquals("committed",fact.get("state"));assertEquals(order.get("orderId"),((Map<?,?>)fact.get("resources")).get("orderId"));
            System.out.println("业务先提交锁竞争通过：业务连接="+ids.get(0)+"，控制连接="+ids.get(1));
        } finally {pool.shutdownNow();}
    }
    @Test void legacyPaymentRejectsTaskGrantBeforeWaitingForOrderLock(){
        // 锁序防回归：本连接锁住订单，旧领域入口在另一连接仍须立即拒绝task grant，不先等待订单锁。
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var order=tools.execute("create_group_buy_order",fixture.creation(quote));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{tx.execute(status->{orders.findOwnedForUpdate(fixture.scope.user,(String)order.get("orderId")).orElseThrow();
            var future=pool.submit(()->{try{groupOrders.payConfirmed(fixture.scope.user,fixture.scope.session,(String)order.get("orderId"),(String)order.get("paymentConfirmationGrantId"));return "unexpected_payment";}
                catch(ToolBoundaryException denied){return denied.getCode();}});
            try{assertEquals("FORBIDDEN",future.get(3,java.util.concurrent.TimeUnit.SECONDS));}catch(Exception failure){throw new IllegalStateException("旧入口先等待了领域锁或未按绑定拒绝",failure);}return null;});}
        finally{pool.shutdownNow();}
    }
    /** 明确Agent检查点替身，只推进控制组件；真实跨Agent恢复留TL-05，不冒称线上证据。 */
    void finishControl(Map<String,Object> task,String action){
        var command=controls.prepare(fixture.scope.user,fixture.scope.session,Map.of("requestId",UUID.randomUUID().toString(),"action",action,"targetTaskId",task.get("conversationTaskId"),
            "expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",fixture.scope.directory().get("focusVersion")));String id=(String)command.get("commandId");
        var wire=controls.claim(fixture.scope.user,fixture.scope.session,id,"synthetic-control-owner",java.time.Instant.now().plusSeconds(1));
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)wire.get("targets");var checkpoints=new ArrayList<Map<String,Object>>();
        for(var target:targets){var point=new LinkedHashMap<String,Object>();point.put("conversationTaskId",target.get("conversationTaskId"));point.put("runId",target.get("runId"));point.put("checkpointVersion",1);checkpoints.add(point);}
        var ack=new LinkedHashMap<String,Object>();ack.put("schemaVersion","2.2");ack.put("commandId",id);ack.put("scope",wire.get("scope"));ack.put("status","checkpoint_saved");ack.put("writeOutcome","none");ack.put("checkpoints",checkpoints);ack.put("reasonCode",null);
        controls.acknowledge(fixture.scope.user,fixture.scope.session,id,ack);controls.commit(fixture.scope.user,fixture.scope.session,id);
    }
    @Test void resumeRequiresFreshActionVersionAndNeverReplaysOldPreparation(){
        // S15：暂停撤销授权，恢复即使同run也不能读取旧准备缓存，新版本准备签发新grant。
        var old=fixture.quote(1);finishControl(AgentTaskTestFixtures.current(fixture.scope.directory()),"pause");
        finishControl(AgentTaskTestFixtures.paused(fixture.scope.directory()).get(0),"resume");fixture.admit("合成恢复购买");
        assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,()->fixture.quote(1)).getCode());var fresh=fixture.quote(2);
        assertNotEquals(old.get("quoteId"),fresh.get("quoteId"));assertNotEquals(old.get("confirmationGrantId"),fresh.get("confirmationGrantId"));
        assertTrue(grants.findByGrantId((String)old.get("confirmationGrantId")).orElseThrow().isRevoked());
        assertEquals(3L,grants.findByGrantId((String)fresh.get("confirmationGrantId")).orElseThrow().getExecutionGeneration());
    }
    @Test void cancelPausedTaskDoesNotRevokeForegroundGrantOrSummary(){
        // S22：实际两个task的授权；后台取消只撤销自身，前台有效grant和等待确认摘要保持不变。
        fixture.quote(1);finishControl(AgentTaskTestFixtures.current(fixture.scope.directory()),"pause");
        var front=registry.create(fixture.scope.user,fixture.scope.session,"transaction","group_buy_order","合成第二购买");String frontId=AgentTaskTestFixtures.id(front);
        fixture.turn=turns.admitTurn(fixture.scope.user,fixture.scope.session,new AgentTaskTurnService.Input("front-input","合成新购买",frontId,((Number)front.get("taskVersion")).longValue(),((Number)fixture.scope.directory().get("focusVersion")).longValue(),true));
        var quote=fixture.quote(2);fixture.awaitConfirmation(Map.of("action","create_group_buy_order","resourceId",quote.get("quoteId"),"title","合成前台确认","amountCents",quote.get("totalCents"),
            "ruleVersion",quote.get("confirmationRuleVersion"),"dealTitle","合成双份套餐","quantity",2));
        var before=fixture.scope.directory();finishControl(AgentTaskTestFixtures.paused(before).get(0),"cancel");var after=fixture.scope.directory();
        assertEquals(before.get("currentTask"),after.get("currentTask"));assertEquals(before.get("focusVersion"),after.get("focusVersion"));
        assertFalse(grants.findByGrantId((String)quote.get("confirmationGrantId")).orElseThrow().isRevoked());
        assertEquals(frontId,jdbc.queryForObject("SELECT pending_confirmation_task_id FROM agent_sessions WHERE session_id=?",String.class,fixture.scope.session));
    }
    @Test void noOpReplyDoesNotRevokeOrExtendExistingGrant(){
        // S22：无工具回复只记录自身来源/事件，不换前台trace，不续签或撤销有效授权。
        var quote=fixture.quote(1);String id=(String)quote.get("confirmationGrantId");var before=grants.findByGrantId(id).orElseThrow();var expires=before.getExpiresAt();String trace=fixture.trace();
        var noOp=turns.admitTurn(fixture.scope.user,fixture.scope.session,new AgentTaskTurnService.Input("noop-input","合成问候",null,0,0,false));
        turns.completeReply(fixture.scope.user,fixture.scope.session,noOp.turnId(),"你好");var after=grants.findByGrantId(id).orElseThrow();
        assertEquals(expires,after.getExpiresAt());assertFalse(after.isRevoked());assertFalse(after.isConsumed());
        assertEquals(trace,jdbc.queryForObject("SELECT trace_id FROM agent_sessions WHERE session_id=?",String.class,fixture.scope.session));
    }
}
