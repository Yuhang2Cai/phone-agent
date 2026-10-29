// 秒杀真实MySQL积压验证：使用实际自动调度，暂停期间回读持久账本，恢复后验证100笔到期补偿门槛。
package com.fandazi.feature.seckill;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.catalog.*;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 独占本任务库运行；调用者须先停止本任务其他扫描进程，不能与UI服务同时形成第二个写者。 */
@EnabledIfEnvironmentVariable(named="SECKILL_MYSQL_BACKLOG_TEST",matches="true")
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false","seckill.expiry.enabled=true",
    "agent.task-lifecycle.enabled=false","spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class SeckillMysqlBacklogTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeckillTimeSource time;
    @Autowired SeckillExpiryScheduler scheduler;
    @Autowired SeckillExpiryWorker worker;
    @Autowired PurchaseQuoteService quotes;
    @Autowired GroupBuyOrderService orders;

    /** 复用已登记的专用端口和私有凭据校验，不允许写入其他任务或生产数据库。 */
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){SeckillMysqlSmokeTest.database(properties);}

    /** 停写持续覆盖三个扫描周期；恢复后不手工调用expire，以真实后台完成时间判断5秒门槛。 */
    @Test void pausedWriterKeepsLedgerStableAndHundredBacklogClosesWithinFiveSeconds() throws Exception {
        assertThat(jdbc.queryForObject("SELECT VERSION()",String.class)).startsWith("8.");
        assertThat(scheduler.snapshot().get("writerId")).isEqualTo("seckill-expiry");
        assertThat(scheduler.pauseAndDrain(5000)).isTrue();
        assertThat(worker.snapshot()).containsEntry("backgroundWriters",0).containsEntry("accepting",false).containsEntry("activeScans",0);
        var tx=new TransactionTemplate(transactions);
        var activity=tx.execute(status->createActivity());
        var expected=new LinkedHashSet<String>();
        try {
            // 建单仍经过真实报价、确认凭证、库存与个人配额事务，不用直接插入订单替代业务流程。
            for(int i=0;i<100;i++) {
                Long user=tx.execute(status->{String id="sk-backlog-"+UUID.randomUUID();var value=new UserEntity(id,id+"@example.invalid","synthetic-hash");em.persist(value);em.flush();return value.getId();});
                String session="backlog-"+UUID.randomUUID();var quote=quotes.createForPage(user,session,activity.getDeal().getPublicId(),1);
                var order=orders.createFromQuote(user,session,quotes.owned(user,(String)quote.get("quoteId")),(String)quote.get("confirmationGrant"),UUID.randomUUID().toString());
                assertThat(expected.add((String)order.get("orderId"))).isTrue();
            }
            // 仅构造服务恢复时已存在的到期积压；保留300秒差值，不作为真实等待300秒的替代证据。
            assertThat(jdbc.update("UPDATE group_buy_orders SET payment_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,created_at=UTC_TIMESTAMP(6)+INTERVAL 8 HOUR-INTERVAL 301 SECOND WHERE seckill_activity_id=?",activity.getId())).isEqualTo(100);
            var before=facts(activity.getId());assertThat(before).hasSize(100);
            assertThat(before.stream().map(row->row.get("public_id")).toList()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isZero();
            // 持久化读取跨过多个真实调度周期；暂停门禁必须阻止原订单、预留和额度发生暗写。
            Thread.sleep(3100);
            assertThat(facts(activity.getId())).isEqualTo(before);
            assertThat(worker.snapshot()).containsEntry("backgroundWriters",0).containsEntry("activeScans",0);
            long start=System.nanoTime();scheduler.resume();int remaining=100;
            while(System.nanoTime()-start<5_000_000_000L) {
                remaining=jdbc.queryForObject("SELECT count(*) FROM group_buy_orders WHERE seckill_activity_id=? AND status<>'CLOSED_TIMEOUT'",Integer.class,activity.getId());
                if(remaining==0)break;
                Thread.sleep(25);
            }
            double elapsed=(System.nanoTime()-start)/1_000_000.0;
            System.out.printf(Locale.ROOT,"秒杀真实积压：数量=100，剩余=%d，恢复至全部持久关闭毫秒=%.3f%n",remaining,elapsed);
            assertThat(remaining).as("100笔到期积压必须在恢复后5秒内全部关闭").isZero();
            assertThat(elapsed).isLessThanOrEqualTo(5000.0);
            // 逐单核对释放与个人额度，再核总库存；单纯状态计数不能证明库存守恒。
            var after=facts(activity.getId());assertThat(after).hasSize(100);
            for(var row:after) {
                assertThat(row).containsEntry("order_status","CLOSED_TIMEOUT").containsEntry("reservation_status","RELEASED");
                assertThat(((Number)row.get("reserved_count")).intValue()).isZero();
                assertThat(((Number)row.get("committed_count")).intValue()).isZero();
                assertThat(row.get("paid_total_amount")).isNull();
                assertThat(((Number)row.get("deadline_micros")).longValue()).isEqualTo(300000000L);
            }
            assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isEqualTo(100);
            // 再次暂停/恢复后仍不能重复释放；完整终态账本保持一致。
            assertThat(scheduler.pauseAndDrain(5000)).isTrue();scheduler.resume();Thread.sleep(1100);
            assertThat(facts(activity.getId())).isEqualTo(after);
            assertThat(jdbc.queryForObject("SELECT inventory FROM deal_packages WHERE id=?",Integer.class,activity.getDeal().getId())).isEqualTo(100);
        } finally {scheduler.pauseAndDrain(5000);}
    }

    /** 返回公开订单身份与无秘密账本字段；稳定排序使停写前后逐行比较有确定性。 */
    private List<Map<String,Object>> facts(Long activityId) {
        return jdbc.queryForList("SELECT o.public_id,o.status AS order_status,o.paid_total_amount,o.closed_at,r.status AS reservation_status,q.reserved_count,q.committed_count,TIMESTAMPDIFF(MICROSECOND,o.created_at-INTERVAL 8 HOUR,o.payment_expires_at) AS deadline_micros FROM group_buy_orders o JOIN seckill_reservations r ON r.order_id=o.id JOIN seckill_quotas q ON q.id=r.quota_id WHERE o.seckill_activity_id=? ORDER BY o.public_id",activityId);
    }

    /** 使用标准UUID建立独立合成门店，避免后台验收夹具破坏Android公开目录解析。 */
    private SeckillActivityEntity createActivity() {
        var now=time.now();var date=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var store=new StoreEntity(UUID.randomUUID().toString(),"秒杀后台验收","后台积压验收小馆","310000","合成地址","000","中餐",BigDecimal.ZERO,BigDecimal.ZERO,3990,true,"全天",null);em.persist(store);
        var deal=new DealPackageEntity(UUID.randomUUID().toString(),store,"积压验证专用券","后台恢复用合成套餐",3990,7900,100,date.plusDays(1),date.plusDays(30),true,1,1);deal.replaceCompareFacts(2,null,"仅隔离验收");em.persist(deal);
        var activity=new SeckillActivityEntity(UUID.randomUUID().toString(),deal,now.minusSeconds(10),now.plusSeconds(3600));em.persist(activity);em.flush();return activity;
    }
}
