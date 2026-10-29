// 超时结算测试：使用真实持久事务验证重复关闭、支付互斥和库存/额度守恒。
package com.fandazi.feature.seckill;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 不给整个用例包测试事务，保证创建、每次关闭和回读是实际独立提交。 */
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seckill-expiry;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class SeckillExpiryTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired GroupBuyOrderService orders;
    @Autowired PurchaseQuoteService quotes;
    @Autowired SeckillExpiryService expiry;
    @Autowired SeckillExpiryWorker worker;
    @MockitoBean SeckillTimeSource time;
    /** 只保存当前用例的资源身份和权威创建时刻，跨用例不共享活动或额度。 */
    private record Prepared(Long user,Long deal,String order,Instant created){}

    /** 经真实领域服务创建一份唯一库存订单，后续测试只操作此独立活动。 */
    private Prepared prepare(){
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        when(time.now()).thenReturn(now);
        var tx=new TransactionTemplate(transactions);
        return tx.execute(status->{
            String identity="sk-expiry-"+UUID.randomUUID();
            var user=new UserEntity(identity,identity+"@example.invalid","test-hash");em.persist(user);
            var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(10),1,1);
            var quote=quotes.createForPage(user.getId(),"expiry-session",activity.getDeal().getPublicId(),1);
            var order=orders.createFromQuote(user.getId(),"expiry-session",quotes.owned(user.getId(),(String)quote.get("quoteId")),
                    (String)quote.get("confirmationGrant"),"expiry-"+UUID.randomUUID());
            return new Prepared(user.getId(),activity.getDeal().getId(),(String)order.get("orderId"),now);
        });
    }

    /** 截止等号应关闭；重复扫描不得再次归还库存或减少个人额度，防止幂等释放回归。 */
    @Test void exactDeadlineClosesAndRepeatedCallsOnlyReleaseOnce(){
        var p=prepare();when(time.now()).thenReturn(p.created().plusSeconds(300));
        assertThat(expiry.expire(p.order())).as("到支付等号必须关闭并释放").isTrue();
        assertThat(expiry.expire(p.order())).as("重复关闭不能再次释放").isFalse();
        assertClosed(p);
    }

    /** 未到支付截止不得提前关闭；已支付订单永不释放，防止扫描侵犯已购买权益。 */
    @Test void beforeDeadlineDoesNotCloseAndPaidOrderNeverReleases(){
        var p=prepare();when(time.now()).thenReturn(p.created().plusMillis(299999));
        assertThat(expiry.expire(p.order())).isFalse();
        assertThat(orders.pay(p.user(),p.order(),"expiry-pay").get("status")).isEqualTo("PAID");
        when(time.now()).thenReturn(p.created().plusSeconds(400));
        assertThat(expiry.expire(p.order())).isFalse();
        assertThat(stock(p)).isZero();
        assertThat(jdbc.queryForObject("SELECT r.status FROM seckill_reservations r JOIN group_buy_orders o ON o.id=r.order_id WHERE o.public_id=?",String.class,p.order())).isEqualTo("COMMITTED");
    }

    /** 两个关闭执行者同时处理同一订单，最终只能提交一次释放并保持全部账本一致。 */
    @Test void twoClosersCommitOneRelease() throws Exception {
        var p=prepare();when(time.now()).thenReturn(p.created().plusSeconds(300));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            // 同时进入两个真实事务；H2用于定向回归，最终仍须独立真实MySQL两实例与100轮竞争。
            var first=pool.submit(()->{start.await();return expiry.expire(p.order());});
            var second=pool.submit(()->{start.await();return expiry.expire(p.order());});start.countDown();
            int changed=(first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0);
            assertThat(changed).as("两个关闭者只能一个实际结算").isEqualTo(1);assertClosed(p);
        }finally{pool.shutdownNow();}
    }

    /** 维护暂停必须阻止后台写入；恢复后只补偿原订单，验证维护门禁与交易扫描衔接。 */
    @Test void maintenanceStopsWriterAndResumeReconcilesOriginalOrder(){
        var p=prepare();
        assertThat(worker.pauseAndDrain(1000)).as("维护停写必须排空真实本进程写者").isTrue();
        try{
            when(time.now()).thenReturn(p.created().plusSeconds(300));
            assertThat(worker.scanOnce()).isZero();
            assertThat(stock(p)).isZero();
            assertThat(orders.detail(p.user(),p.order()).get("status")).isEqualTo("PENDING_PAYMENT");
        }finally{worker.resume();}
        assertThat(worker.scanOnce()).as("恢复后必须补偿原到期订单").isEqualTo(1);
        assertClosed(p);
    }

    /** 回读当前套餐唯一库存，避免用全库汇总掩盖跨活动错误。 */
    private int stock(Prepared p){return jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,p.deal());}
    /** 对照订单、库存、预留和个人额度，已结束活动不因释放而再次变为可售。 */
    private void assertClosed(Prepared p){
        assertThat(orders.detail(p.user(),p.order()).get("status")).isEqualTo("CLOSED_TIMEOUT");
        assertThat(stock(p)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT r.status FROM seckill_reservations r JOIN group_buy_orders o ON o.id=r.order_id WHERE o.public_id=?",String.class,p.order())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT q.reserved_count+q.committed_count FROM seckill_quotas q WHERE q.user_id=?",Integer.class,p.user())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seckill_activities a WHERE a.deal_id=? AND a.ends_at<=?",Integer.class,p.deal(),java.sql.Timestamp.from(p.created().plusSeconds(300)))).isEqualTo(1);
    }
}
