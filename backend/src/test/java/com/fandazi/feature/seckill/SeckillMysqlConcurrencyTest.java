// 真实MySQL并发验证：保留默认可重复读隔离，覆盖最后库存及已有快照下的同请求重放。
package com.fandazi.feature.seckill;
import com.fandazi.feature.agent.ToolBoundaryException;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 只允许与真实冒烟相同的专用库；所有失败样本保留，不把死锁/技术异常当库存拒绝。 */
@EnabledIfEnvironmentVariable(named="SECKILL_MYSQL_TEST",matches="true")
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false","spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.maximum-pool-size=30",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class SeckillMysqlConcurrencyTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeckillTimeSource time;
    @Autowired PurchaseQuoteService quotes;
    @Autowired GroupBuyOrderService orders;
    @Autowired SeckillExpiryService expiry;
    /** 复用同一严格地址约束及私有环境，不复制凭据到源码。 */
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){SeckillMysqlSmokeTest.database(properties);}
    /** 仅保留合成用户与报价身份，线程结果不含访问令牌或密码。 */
    private record Request(Long user,String quote,String grant){}
    /** 每个用户单独持久化，再经实际报价服务取得授权；不预扣库存。 */
    private Request request(SeckillActivityEntity activity){
        Long user=new TransactionTemplate(transactions).execute(status->{
            String id="sk-race-"+UUID.randomUUID();var account=new UserEntity(id,id+"@example.invalid","test-hash");em.persist(account);em.flush();return account.getId();
        });
        var prepared=quotes.createForPage(user,"race-session",activity.getDeal().getPublicId(),1);
        return new Request(user,(String)prepared.get("quoteId"),(String)prepared.get("confirmationGrant"));
    }
    /** 只创建本测试活动；数据库权威时间决定窗口，未来使用日期不妨碍当前秒杀。 */
    private SeckillActivityEntity activity(){
        var now=time.now();return new TransactionTemplate(transactions).execute(status->SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),1,1));
    }
    /** 返回原订单号或固定业务拒绝码；其他异常原样由Future保留为测试失败。 */
    private String create(Request request,String key){
        try{return (String)orders.createFromQuote(request.user(),"race-session",quotes.owned(request.user(),request.quote()),request.grant(),key).get("orderId");}
        catch(ToolBoundaryException failure){return "REJECT:"+failure.getCode();}
    }

    /** 真实MySQL下20位用户竞争最后一份，仅一人成功；死锁和技术异常不能冒充正常库存拒绝。 */
    @Test void twentyUsersCompeteForLastStockWithoutTechnicalErrors() throws Exception {
        var activity=activity();var requests=new ArrayList<Request>();for(int i=0;i<20;i++)requests.add(request(activity));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(20);
        try{
            var futures=new ArrayList<Future<String>>();
            for(var request:requests)futures.add(pool.submit(()->{start.await();return create(request,"last-stock-"+UUID.randomUUID());}));
            start.countDown();var results=new ArrayList<String>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(v->!v.startsWith("REJECT:")).count()).isEqualTo(1);
            assertThat(results.stream().filter(v->v.startsWith("REJECT:")).toList()).hasSize(19).containsOnly("REJECT:SOLD_OUT");
            assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM group_buy_orders WHERE seckill_activity_id=?",Integer.class,activity.getId())).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    /** 两个事务先建立旧快照再提交同一请求，仍应读回唯一原订单，防止一致性快照导致重复建单。 */
    @Test void sameRequestReturnsOriginalFactEvenWhenBothTransactionsAlreadyHaveSnapshots() throws Exception {
        var activity=activity();var request=request(activity);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        Callable<String> competing=()->{
            var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try{return tx.execute(status->{
                // 模拟Agent外层事务已读取报价的合法调用：两者都先建立旧快照，再竞争用户锁。
                quotes.owned(request.user(),request.quote());ready.countDown();
                try{if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("并发屏障超时");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return create(request,"same-request");
            });}catch(RuntimeException failure){
                // 技术错误也必须成为明确失败结果，不能被当作允许的库存拒绝或有效幂等重放。
                return "FAILED:"+failure.getClass().getSimpleName();
            }
        };
        try{
            var first=pool.submit(competing);var second=pool.submit(competing);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            String one=first.get(20,TimeUnit.SECONDS),two=second.get(20,TimeUnit.SECONDS);
            assertThat(one).as("同键首个结果必须是订单事实").doesNotStartWith("REJECT:").doesNotStartWith("FAILED:");
            assertThat(two).as("同键并发必须返回同一订单，不能因为旧快照误报冲突").isEqualTo(one);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM group_buy_orders WHERE seckill_activity_id=?",Integer.class,activity.getId())).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }

    /** 同用户两会话同时提交不同报价，累计限额只允许一笔；库存仍有余量，不能靠售罄代替限购。 */
    @Test void twoSessionsShareOneCumulativeQuota() throws Exception {
        var now=time.now();var activity=new TransactionTemplate(transactions).execute(status->
                SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),10,1));
        var one=request(activity);var other=quotes.createForPage(one.user(),"other-session",activity.getDeal().getPublicId(),1);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{start.await();return create(one,"quota-first");});
            var second=pool.submit(()->{start.await();try{
                return (String)orders.createFromQuote(one.user(),"other-session",quotes.owned(one.user(),(String)other.get("quoteId")),
                        (String)other.get("confirmationGrant"),"quota-second").get("orderId");
            }catch(ToolBoundaryException denied){return "REJECT:"+denied.getCode();}});
            start.countDown();var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(value->value.startsWith("REJECT:")).toList()).containsExactly("REJECT:LIMIT_REACHED");
            assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT reserved_count+committed_count FROM seckill_quotas WHERE activity_id=? AND user_id=?",Integer.class,activity.getId(),one.user())).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }

    /** 建单完成后注入事务失败：订单、回执、库存与配额必须一起回滚，原请求随后可以唯一提交。 */
    @Test void failedOuterTransactionLeavesNoPartialReservation(){
        var activity=activity();var request=request(activity);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            String result=create(request,"rollback-request");assertThat(result).doesNotStartWith("REJECT:");
            // 在领域已经刷新数据库后抛出确定故障，覆盖提交前回滚，不冒充进程断电验收。
            em.flush();throw new IllegalStateException("合成提交前故障");
        })).isInstanceOf(IllegalStateException.class).hasMessage("合成提交前故障");
        assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_buy_orders WHERE seckill_activity_id=?",Integer.class,activity.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seckill_purchase_requests WHERE user_id=?",Integer.class,request.user())).isZero();
        assertThat(create(request,"rollback-request")).doesNotStartWith("REJECT:");
    }

    /** 100轮真实数据库支付/关闭并发，每轮核对订单、预留、库存及用户额度，而非仅合计成功数。 */
    @Test void hundredPaymentAndExpiryRacesKeepExactlyOneSettlement() throws Exception {
        var pool=Executors.newFixedThreadPool(2);int paidCount=0,closedCount=0;
        try{
            for(int round=0;round<100;round++){
                var activity=activity();var request=request(activity);String orderId=create(request,"settlement-"+round);
                assertThat(orderId).doesNotStartWith("REJECT:");
                // 仅定向竞态压缩等待：以数据库时间设置临界前/已到期各50轮，真实300秒另行验收。
                int offsetMicros=round%2==0?30000:-1000;
                jdbc.update("UPDATE group_buy_orders SET payment_expires_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)),created_at=TIMESTAMPADD(MICROSECOND,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR)) WHERE public_id=?",
                        offsetMicros,offsetMicros-300000000,orderId);
                var start=new CountDownLatch(1);
                var pay=pool.submit(()->{start.await();try{return (String)orders.pay(request.user(),orderId,"pay-"+orderId).get("status");}
                    catch(ToolBoundaryException denied){return "REJECT:"+denied.getCode();}});
                var close=pool.submit(()->{start.await();return expiry.expire(orderId);});start.countDown();
                String payment=pay.get(10,TimeUnit.SECONDS);close.get(10,TimeUnit.SECONDS);
                assertThat(payment).as("第%d轮支付结果",round).isIn("PAID","REJECT:PAYMENT_EXPIRED","REJECT:ORDER_CLOSED");
                // 扫描恰好先于截止且支付随后拒绝时，下一次有界补偿应完成关闭，不改变已支付事实。
                Thread.sleep(35);expiry.expire(orderId);
                var facts=jdbc.queryForMap("SELECT o.status AS order_state,r.status AS reservation_state,q.reserved_count,q.committed_count,d.inventory FROM group_buy_orders o JOIN seckill_reservations r ON r.order_id=o.id JOIN seckill_quotas q ON q.id=r.quota_id JOIN deal_packages d ON d.id=o.deal_id WHERE o.public_id=?",orderId);
                assertThat(((Number)facts.get("reserved_count")).intValue()).as("第%d轮预留必须结算",round).isZero();
                if("PAID".equals(facts.get("order_state"))){
                    paidCount++;assertThat(facts.get("reservation_state")).isEqualTo("COMMITTED");
                    assertThat(((Number)facts.get("committed_count")).intValue()).isEqualTo(1);
                    assertThat(((Number)facts.get("inventory")).intValue()).isZero();
                }else{
                    closedCount++;assertThat(facts.get("order_state")).isEqualTo("CLOSED_TIMEOUT");
                    assertThat(facts.get("reservation_state")).isEqualTo("RELEASED");
                    assertThat(((Number)facts.get("committed_count")).intValue()).isZero();
                    assertThat(((Number)facts.get("inventory")).intValue()).isEqualTo(1);
                }
                assertThat(expiry.expire(orderId)).as("第%d轮重复关闭不得再释放",round).isFalse();
            }
            assertThat(paidCount+closedCount).isEqualTo(100);
            System.out.printf("秒杀结算竞态：总轮数=100，已支付=%d，超时关闭=%d，逐单账本一致。%n",paidCount,closedCount);
        }finally{pool.shutdownNow();}
    }
}
