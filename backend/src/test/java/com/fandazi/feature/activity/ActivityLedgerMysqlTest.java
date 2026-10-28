// 活动真实 MySQL 测试：独立合成库验证周期唯一、20 方领奖竞争和账本回滚，不连接模型或真实支付。
package com.fandazi.feature.activity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 只有显式启用且库名为本任务专用库才运行；跳过不计为真实事务通过。 */
@EnabledIfEnvironmentVariable(named="ACTIVITY_MYSQL_ENABLED", matches="true")
class ActivityLedgerMysqlTest {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static final LocalDateTime NOW = LocalDateTime.of(2026,10,28,12,0);
    private ActivityService service;
    private long user;
    private String definition;

    /** 先确认独立库并运行真实迁移；仅创建新合成行，绝不清理共享业务数据。 */
    @BeforeAll static void prepareDatabase() {
        String url=System.getenv("ACTIVITY_DB_URL");
        assertTrue(url.contains("/fandazi_activity_rn_20261028?"),"只能运行本任务专用库");
        var source=new DriverManagerDataSource(url,System.getenv("ACTIVITY_DB_USER"),System.getenv("ACTIVITY_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        assertTrue(jdbc.queryForObject("select version()",String.class).startsWith("8."));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("select @@transaction_isolation",String.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }
    /** 每个场景使用新用户和新定义，不用串行清库掩盖并发问题。 */
    @BeforeEach void prepareScenario() {
        service=new ActivityService(jdbc,manager,new ActivityClock(Clock.fixed(NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC)));
        String name="activity-"+key();
        jdbc.update("insert into app_users(username,email,password_hash) values(?,?,?)",name,name+"@example.invalid","synthetic-unusable-hash");
        user=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
        definition=definition(1,10);
    }
    /** 同一天重复领取返回同实例；幂等键绑定预期版本，不能被新内容占用。 */
    @Test void acceptanceFreezesSnapshotAndDeduplicatesCycle() {
        String request=key(); var first=service.accept(user,definition,1,request);
        assertNotNull(first.get("instanceId"),"领取必须持久化并返回真实实例");
        assertEquals(first.get("instanceId"),service.accept(user,definition,1,key()).get("instanceId"));
        assertEquals(first.get("instanceId"),service.accept(user,definition,1,request).get("instanceId"));
        var error=assertThrows(ActivityException.class,()->service.accept(user,definition,2,request));
        assertEquals("IDEMPOTENCY_CONFLICT",error.reasonCode());
        assertEquals(1,count("activity_instances","user_id=?",user));
    }
    /** 同实例 20 个不同请求键同时进入，数据库只能新增一个凭据、一条奖励流水和十金币。 */
    @Test void twentyClaimsProduceOneReward() throws Exception {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");
        assertNotNull(instance,"并发前必须有已领取实例"); paidOrder(user);
        var gate=new CyclicBarrier(20);var pool=Executors.newFixedThreadPool(20);
        try {
            var futures=new ArrayList<Future<Map<String,Object>>>();
            for(int index=0;index<20;index++) futures.add(pool.submit(()->{gate.await(15,TimeUnit.SECONDS);return service.claim(user,instance,key());}));
            var receipts=new HashSet<Object>();
            for(var future:futures)receipts.add(future.get(40,TimeUnit.SECONDS).get("receiptId"));
            assertEquals(1,receipts.size()); assertFalse(receipts.contains(null));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(1,count("activity_reward_grants","user_id=?",user));
        assertEquals(1,count("coin_transactions","user_id=?",user)); assertConservation(10);
    }
    /** 同一有效订单允许贡献不同任务；两个任务并行发放均保留且余额不丢更新。 */
    @Test void distinctTasksDoNotLoseAccountUpdates() throws Exception {
        String first=(String)service.accept(user,definition,1,key()).get("instanceId");
        String second=(String)service.accept(user,definition(1,30),1,key()).get("instanceId");
        assertNotNull(first,"不同任务必须先落真实实例"); paidOrder(user);
        var pool=Executors.newFixedThreadPool(2);var gate=new CyclicBarrier(2);
        try {
            var a=pool.submit(()->{gate.await();return service.claim(user,first,key());});
            var b=pool.submit(()->{gate.await();return service.claim(user,second,key());});
            assertNotEquals(a.get(30,TimeUnit.SECONDS).get("receiptId"),b.get(30,TimeUnit.SECONDS).get("receiptId"));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(2,count("coin_transactions","user_id=?",user)); assertConservation(40);
    }
    /** 外层事务在发放后失败，所有内部凭据/订单依据/流水/余额须一起回滚。 */
    @Test void laterTransactionFailureRollsBackEntireGrant() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");
        assertNotNull(instance,"回滚场景必须有真实领取实例");paidOrder(user);
        var error=assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(status->{
            var receipt=service.claim(user,instance,key());assertNotNull(receipt.get("receiptId"));
            throw new IllegalStateException("指定的发放后故障");}));
        assertEquals("指定的发放后故障",error.getMessage());
        assertEquals(0,count("activity_reward_grants","user_id=?",user));
        assertEquals(0,count("coin_transactions","user_id=?",user));assertConservation(0);
    }
    /** 凭据和依据已写、流水尚未写入时由真实MySQL主动失败，防止中途异常留下半账。 */
    @Test void ledgerInsertFailureRollsBackEarlierProofAndGrant() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");
        paidOrder(user);
        String request=key();
        String trigger="activity_fault_"+UUID.randomUUID().toString().replace("-","");
        // 触发器仅匹配本例新建的合成用户；固定表名和随机字母数字名称，不接受外部SQL输入。
        jdbc.execute("create trigger "+trigger+" before insert on coin_transactions for each row begin if NEW.user_id="+user+" and NEW.kind='REWARD' then signal sqlstate '45000' set message_text='activity_test_mid_write'; end if; end");
        try {
            var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->service.claim(user,instance,request));
            assertTrue(failure.getMostSpecificCause().getMessage().contains("activity_test_mid_write"),"必须由指定中途故障触发，不能把连接或语法错误计作通过");
            assertEquals(0,count("activity_reward_grants","user_id=?",user));
            assertEquals(0,count("coin_transactions","user_id=?",user));
            assertEquals(0,count("activity_requests","user_id=? and action='CLAIM'",user));
            assertEquals("NONE",jdbc.queryForObject("select reward_status from activity_instances where instance_id=?",String.class,instance));
            assertConservation(0);
        } finally {
            // 无论断言成败都移除本测试触发器，后续场景不得继承故障状态。
            jdbc.execute("drop trigger if exists "+trigger);
        }
        // 同一原请求在依赖恢复后可正常执行，且仍只生成一份奖励和一条流水。
        assertNotNull(service.claim(user,instance,request).get("receiptId"));
        assertEquals(1,count("activity_reward_grants","user_id=?",user));
        assertEquals(1,count("coin_transactions","user_id=?",user));
        assertConservation(10);
    }
    /** 无有效支付不得发奖；跨用户实例使用统一 404，不能暴露别人的任务状态。 */
    @Test void missingPaymentAndOtherOwnerCannotClaim() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");
        assertNotNull(instance,"权限场景必须有真实实例");
        assertEquals("NOT_READY",assertThrows(ActivityException.class,()->service.claim(user,instance,key())).reasonCode());
        assertEquals(404,assertThrows(ActivityException.class,()->service.claim(user+10000000,instance,key())).status());
        assertEquals(0,count("coin_transactions","user_id=?",user));
    }
    /** 真实退款申请行冻结未发奖资格；拒绝后恢复，不能永久抹掉支付事实。 */
    @Test void pendingRefundFreezesAndRejectedRefundRestoresEligibility() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");String order=paidOrder(user);
        refund(order,"REQUESTED");
        assertEquals("NOT_READY",assertThrows(ActivityException.class,()->service.claim(user,instance,key())).reasonCode());
        jdbc.update("update refund_requests set status='REJECTED' where user_id=?",user);
        assertNotNull(service.claim(user,instance,key()).get("receiptId"));assertConservation(10);
    }
    /** 受控适配器与冲正处于同一事务，重复终态只撤回一次且原领奖键不会再次发币。 */
    @Test void terminalRefundReversesExactlyOnceAndNeverRegrants() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");String order=paidOrder(user);String request=key();
        Object receipt=service.claim(user,instance,request).get("receiptId");refund(order,"REQUESTED");
        Runnable terminal=()->jdbc.update("update refund_requests set status='COMPLETED' where user_id=?",user);
        service.coordinateRefundCompletion(user,order,terminal);service.coordinateRefundCompletion(user,order,terminal);
        assertEquals("REVERSED",service.detail(user,instance).get("status"),"终态必须驱动唯一冲正");
        assertEquals(receipt,service.claim(user,instance,request).get("receiptId"));assertConservation(0);
        assertEquals(2,count("coin_transactions","user_id=?",user));
    }
    /** 不只检查最早证明订单：窗口内仍有备用有效订单时保留奖励。 */
    @Test void redundantPaidOrderPreservesRewardAfterTerminalRefund() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");String order=paidOrder(user);paidOrder(user);
        service.claim(user,instance,key());refund(order,"REQUESTED");
        service.coordinateRefundCompletion(user,order,()->jdbc.update("update refund_requests set status='COMPLETED' where user_id=?",user));
        assertEquals("COMPLETED",jdbc.queryForObject("select status from refund_requests where user_id=?",String.class,user),"必须实际执行受控终态回调");
        assertEquals("CLAIMED",service.detail(user,instance).get("status"));assertConservation(10);
        assertEquals(1,count("coin_transactions","user_id=?",user));
    }
    /** 终态适配器后续失败必须回滚退款和冲正，不能让外部确认与账本分离提交。 */
    @Test void terminalAdapterFailureLeavesRefundAndLedgerUnchanged() {
        String instance=(String)service.accept(user,definition,1,key()).get("instanceId");String order=paidOrder(user);
        service.claim(user,instance,key());refund(order,"REQUESTED");
        var error=assertThrows(IllegalStateException.class,()->service.coordinateRefundCompletion(user,order,()->{
            jdbc.update("update refund_requests set status='COMPLETED' where user_id=?",user);throw new IllegalStateException("指定终态故障");}));
        assertEquals("指定终态故障",error.getMessage());assertConservation(10);
        assertEquals("REQUESTED",jdbc.queryForObject("select status from refund_requests where user_id=?",String.class,user));
    }
    /** 次日恢复前日未知领取必须按原请求回读，不能误认当天新的任务实例。 */
    @Test void recoveryUsesOriginalRequestAcrossDatesAndOwners() {
        String request=key();String original=(String)service.accept(user,definition,1,request).get("instanceId");
        var tomorrow=new ActivityService(jdbc,manager,new ActivityClock(Clock.fixed(NOW.plusDays(1).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC)));
        String next=(String)tomorrow.accept(user,definition,1,key()).get("instanceId");assertNotEquals(original,next);
        var recovered=(Map<?,?>)tomorrow.tasks(user,request).get("recoveredTask");
        assertNotNull(recovered,"原键查询必须返回旧实例，而不是按当前周期猜测");assertEquals(original,recovered.get("instanceId"));
        assertNull(tomorrow.tasks(user+10000000,request).get("recoveredTask"));
        assertEquals(401,assertThrows(ActivityException.class,()->tomorrow.tasks(null,request)).status());
        assertEquals(2,count("activity_instances","user_id=?",user));
    }
    /** 同活动周期的连续任务中断后，即使宽限期已过，列表仍应呈现旧实例而不是可重新领取。 */
    @Test void endedStreakRemainsVisibleForItsCampaign() {
        jdbc.update("update activity_definitions set task_type='CONSECUTIVE_ORDER_DAYS',day_count=3,starts_at=?,ends_at=? where definition_id=?",NOW.minusDays(20),NOW.plusDays(20),definition);
        var earlier=new ActivityService(jdbc,manager,new ActivityClock(Clock.fixed(NOW.minusDays(10).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC)));
        String original=(String)earlier.accept(user,definition,1,key()).get("instanceId");
        var cards=(List<Map<String,Object>>)service.tasks(user).get("tasks");
        var card=cards.stream().filter(item->definition.equals(item.get("definitionId"))).findFirst().orElseThrow();
        assertEquals(original,card.get("instanceId"),"失败后同周期不可生成新领取入口");assertFalse(((List<?>)card.get("allowedActions")).contains("ACCEPT"));
    }
    /** 新定义只存服务端规则；每次使用 UUID 避免污染其他场景。 */
    private String definition(int target,int reward) {
        String id=key();jdbc.update("insert into activity_definitions values(?,1,?,'DAILY_ORDER_COUNT',?,1,?,?,?, ?,true)",
                id,"合成每日任务",target,reward,key(),NOW.minusDays(1),NOW.plusDays(10));return id;
    }
    /** 生成真实关系行及持久支付时间；多份仍是一个订单，金额只用于订单事实完整性。 */
    private String paidOrder(long owner) {
        String store=key();jdbc.update("insert into stores(public_id,brand_name,name,city_code,address,phone,category,latitude,longitude,average_spend_amount,open,opening_hours) values(?, '合成品牌','合成门店','TEST','合成地址','000','测试',0,0,10,true,'全天')",store);
        long storeId=jdbc.queryForObject("select id from stores where public_id=?",Long.class,store);
        String deal=key();jdbc.update("insert into deal_packages(public_id,store_id,title,description,price_amount,original_price_amount,inventory,valid_from,valid_to,active,purchase_limit,version) values(?,?,'合成套餐','事务验收',10,12,100,?, ?,true,10,1)",deal,storeId,NOW.toLocalDate().minusDays(1),NOW.toLocalDate().plusDays(10));
        long dealId=jdbc.queryForObject("select id from deal_packages where public_id=?",Long.class,deal);
        String order=key();jdbc.update("insert into group_buy_orders(public_id,user_id,store_id,deal_id,idempotency_key,quantity,total_amount,paid_total_amount,paid_at,status,created_at,expires_at,request_fingerprint) values(?,?,?,?,?,3,30,30,?,'PAID',?,?,?)",order,owner,storeId,dealId,key(),NOW,NOW.minusMinutes(1),NOW.plusDays(10),key());
        long orderId=jdbc.queryForObject("select id from group_buy_orders where public_id=?",Long.class,order);
        // 每份券独立保存支付分摊；任一张券退款足以使整个订单不参与新奖励。
        for(int i=0;i<3;i++)jdbc.update("insert into vouchers(public_id,order_id,user_id,deal_id,status,qr_token,expires_at,created_at,allocated_amount) values(?,?,?,?,'UNUSED',?,?,?,10)",key(),orderId,owner,dealId,key(),NOW.plusDays(10),NOW);
        return order;
    }
    /** 仅构造受控数据库事实；真实 RefundService 申请入口另在组合测试中核验。 */
    private void refund(String order,String status) {
        long voucher=jdbc.queryForObject("select v.id from vouchers v join group_buy_orders o on o.id=v.order_id where o.public_id=? order by v.id limit 1",Long.class,order);
        jdbc.update("insert into refund_requests(public_id,voucher_id,user_id,reason,status,created_at,amount) values(?,?,?,'合成退款',?,?,10)",key(),voucher,user,status,NOW);
    }
    /** 账本守恒同时核对账户与流水总和，不能只看响应中的余额。 */
    private void assertConservation(int expected) {
        assertEquals(expected,jdbc.queryForObject("select balance from coin_accounts where user_id=?",Integer.class,user));
        assertEquals(expected,jdbc.queryForObject("select coalesce(sum(amount),0) from coin_transactions where user_id=?",Integer.class,user));
    }
    /** 表名/条件仅来自本测试固定常量，参数仍通过预编译绑定。 */
    private int count(String table,String condition,Object value) {return jdbc.queryForObject("select count(*) from "+table+" where "+condition,Integer.class,value);}
    /** 每个逻辑请求的键生成一次，不因响应超时替换原键。 */
    private static String key(){return UUID.randomUUID().toString();}
}
