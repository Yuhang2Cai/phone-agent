// 秒杀报价边界测试：页面、旧接口和Agent共用权威窗口，报价只保存快照而不预留库存。
package com.fandazi.feature.seckill;

import com.fandazi.feature.agent.ToolBoundaryException;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 固定服务端时钟验证毫秒边界；此定向测试不能替代真实MySQL时间验收。 */
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seckill-quote;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@Transactional
class SeckillQuoteTest {
    @Autowired EntityManager em;
    @Autowired PurchaseQuoteService quotes;
    @MockitoBean SeckillTimeSource time;

    /** Agent恢复报价也必须使用数据库时钟；主机时间在未来不能提前拒绝尚有效的报价。 */
    @Test void quoteRecoveryUsesDatabaseTimeInsteadOfHostClock(){
        Instant databaseNow=Instant.now().minusSeconds(3600);
        var activity=SeckillTestFixture.create(em,databaseNow.minusSeconds(1),databaseNow.plusSeconds(600),10,1);
        String identity="sk-clock-"+UUID.randomUUID();var user=new UserEntity(identity,identity+"@example.invalid","test-hash");em.persist(user);em.flush();
        when(time.now()).thenReturn(databaseNow);
        var result=quotes.createForAgent(user.getId(),"sk-session",activity.getDeal().getPublicId(),1);
        assertThatCode(()->quotes.requireForCreation(user.getId(),(String)result.get("quoteId"))).doesNotThrowAnyException();
    }

    /** 数据库已到截止等号时不能因主机时钟落后继续恢复旧报价。 */
    @Test void quoteRecoveryRejectsDatabaseDeadlineEquality(){
        Instant databaseNow=Instant.now().plusSeconds(3600);
        var activity=SeckillTestFixture.create(em,databaseNow.minusSeconds(1),databaseNow.plusSeconds(60),10,1);
        String identity="sk-clock-"+UUID.randomUUID();var user=new UserEntity(identity,identity+"@example.invalid","test-hash");em.persist(user);em.flush();
        when(time.now()).thenReturn(databaseNow);
        var result=quotes.createForAgent(user.getId(),"sk-session",activity.getDeal().getPublicId(),1);
        when(time.now()).thenReturn(activity.getEndsAt());
        assertThatThrownBy(()->quotes.requireForCreation(user.getId(),(String)result.get("quoteId"))).isInstanceOf(ToolBoundaryException.class);
    }

    /** 单价合法但乘以数量超出订单整数分上限时必须拒绝，不能截断或溢出成错误金额。 */
    @Test void validUnitPriceCannotOverflowOrderAmount(){
        Instant now=Instant.now();var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),100,99);
        String identity="sk-amount-"+UUID.randomUUID();var user=new UserEntity(identity,identity+"@example.invalid","test-hash");em.persist(user);em.flush();
        // 单价和限购各自合法，但乘积超过现有整数金额接口上限时必须明确拒绝，不能返回500或截断金额。
        em.createQuery("update DealPackageEntity d set d.priceCents=:price,d.originalPriceCents=:price where d.id=:id")
                .setParameter("price",99999999).setParameter("id",activity.getDeal().getId()).executeUpdate();em.refresh(activity.getDeal());
        when(time.now()).thenReturn(now);
        assertThatThrownBy(()->quotes.createForPage(user.getId(),"sk-session",activity.getDeal().getPublicId(),99))
                .isInstanceOfSatisfying(ToolBoundaryException.class,e->assertThat(e.getCode()).isEqualTo("AMOUNT_EXCEEDED"));
    }

    @ParameterizedTest
    @CsvSource({"page,-1,NOT_STARTED","legacy,-1,NOT_STARTED","agent,-1,NOT_STARTED",
            "page,0,OK","legacy,0,OK","agent,0,OK",
            "page,999,OK","legacy,999,OK","agent,999,OK",
            "page,1000,ENDED","legacy,1000,ENDED","agent,1000,ENDED"})
    void allQuoteEntriesRespectAuthoritativeWindow(String entry,long offsetMillis,String expected) {
        // 输入准备：券明天可用，活动今天开始；购买窗口必须独立于券使用起日。
        Instant start=Instant.now().plusSeconds(60);
        var activity=SeckillTestFixture.create(em,start,start.plusSeconds(1),100,2);
        String identity="sk-quote-"+UUID.randomUUID();
        var user=new UserEntity(identity,identity+"@example.invalid","test-hash");
        em.persist(user);em.flush();
        when(time.now()).thenReturn(start.plusMillis(offsetMillis));
        if(!"OK".equals(expected)) {
            // 开始前与结束等号处必须返回稳定业务码，不能生成可消费的旧确认。
            assertThatThrownBy(()->quote(entry,user.getId(),activity.getDeal().getPublicId()))
                    .isInstanceOfSatisfying(ToolBoundaryException.class,e->assertThat(e.getCode()).isEqualTo(expected));
            assertThat(em.createQuery("select count(q) from PurchaseQuote q",Long.class).getSingleResult()).isZero();
        } else {
            // 合法窗口内报价携带活动/版本，并在活动结束处失效；确认前不占库存或个人名额。
            var captured=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
            assertThatCode(()->captured.set(quote(entry,user.getId(),activity.getDeal().getPublicId())))
                    .as("活动进行中即使券明天使用也必须可报价").doesNotThrowAnyException();
            Map<String,Object> result=captured.get();
            assertThat(result.get("activityId")).isEqualTo(activity.getPublicId());
            assertThat(((Number)result.get("activityVersion")).longValue()).isEqualTo(activity.getActivityVersion());
            assertThat(result.get("expiresAt")).isEqualTo(activity.getEndsAt().toString());
            assertThat(result.get("serverTime")).isEqualTo(start.plusMillis(offsetMillis).toString());
            assertThat(result.get("totalCents")).isEqualTo(3990);
        }
        // 前后不变量：合法/拒绝报价都不能新增订单、扣减套餐库存。
        em.refresh(activity.getDeal());
        assertThat(activity.getDeal().getInventory()).isEqualTo(100);
        assertThat(em.createQuery("select count(o) from GroupBuyOrderEntity o",Long.class).getSingleResult()).isZero();
    }

    /** 按真实服务入口调用，避免只测新增帮助函数而漏掉历史入口。 */
    private Map<String,Object> quote(String entry,Long user,String deal) {
        return switch(entry) {
            case "page" -> quotes.createForPage(user,"sk-session",deal,1);
            case "legacy" -> quotes.create(user,"sk-session",deal,1);
            default -> quotes.createForAgent(user,"sk-session",deal,1);
        };
    }
}
