// 秒杀订单定向测试：既有创建/支付入口必须遵守活动快照、个人累计额度及独立支付截止。
package com.fandazi.feature.seckill;

import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.agent.ToolBoundaryException;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 事务内断言覆盖领域行为；真实MySQL竞争和跨事务回滚另行验收。 */
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seckill-order;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@Transactional
class SeckillOrderTest {
    @Autowired EntityManager em;
    @Autowired PurchaseQuoteService quotes;
    @Autowired GroupBuyOrderService orders;
    @Autowired ConfirmationGrantService grants;
    @Autowired com.fandazi.feature.voucher.VoucherTokenService tokens;
    @Autowired SeckillExpiryService expiry;
    @MockitoBean SeckillTimeSource time;
    private Long user;
    private Instant now;

    /** 每个场景创建独立账号并冻结权威时刻，避免用例之间共享订单或额度。 */
    @BeforeEach void prepare() {
        String identity="sk-order-"+UUID.randomUUID();
        var account=new UserEntity(identity,identity+"@example.invalid","test-hash");
        em.persist(account);em.flush();user=account.getId();
        now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        when(time.now()).thenReturn(now);
    }

    /** 创建时仅预留一次，支付截止严格为权威创建时间加300秒，不能复用券使用截止。 */
    @Test void orderReservesOnceAndKeepsPaymentDeadlineSeparateFromVoucher() {
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(7200),3,1);
        var quote=quote(activity);
        var result=createSuccessfully(quote,"same-request");
        // 支付严格300秒；券可在未来自然日使用，不能被5分钟订单期限覆盖。
        assertThat(result.get("paymentExpiresAt")).isEqualTo(now.plusSeconds(300).toString());
        assertThat(result.get("createdAt")).isEqualTo(now.toString());
        var repeated=createSuccessfully(quote,"same-request");
        assertThat(repeated.get("orderId")).isEqualTo(result.get("orderId"));
        em.flush();em.refresh(activity.getDeal());
        assertThat(activity.getDeal().getInventory()).isEqualTo(2);
        assertThat(em.createQuery("select count(o) from GroupBuyOrderEntity o",Long.class).getSingleResult()).isEqualTo(1);
    }

    /** 同一用户持有两份报价仍受累计占用与已购限额约束，防止跨会话绕过限购。 */
    @Test void twoQuotesCannotBypassCumulativeLimit() {
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(7200),3,1);
        // 先取得两份报价，证明报价本身不预占；最终下单时必须重新累计校验。
        var first=quote(activity);var second=quote(activity);
        createSuccessfully(first,"first-request");
        assertThatThrownBy(()->create(second,"second-request"))
                .isInstanceOfSatisfying(ToolBoundaryException.class,e->assertThat(e.getCode()).isEqualTo("LIMIT_REACHED"));
    }

    /** 活动版本变化后旧报价确认必须失效，不能按旧价格或旧活动事实继续建单。 */
    @Test void changedActivityInvalidatesPreviouslyConfirmedQuote() {
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(7200),3,1);
        var quote=quote(activity);
        // 测试夹具模拟受控活动输入变更，不提供生产管理端；旧快照不能被当前版本替换。
        em.createQuery("update SeckillActivityEntity a set a.activityVersion=a.activityVersion+1 where a.id=:id")
                .setParameter("id",activity.getId()).executeUpdate();
        em.refresh(activity);
        assertThatThrownBy(()->create(quote,"changed-request"))
                .isInstanceOfSatisfying(ToolBoundaryException.class,e->assertThat(e.getCode()).isEqualTo("ACTIVITY_CHANGED"));
        assertThat(em.createQuery("select count(o) from GroupBuyOrderEntity o",Long.class).getSingleResult()).isZero();
    }

    /** 旧创建入口携带裸套餐授权也不能提前购买秒杀券，防止绕过新报价流程。 */
    @Test void legacyCreateCannotUseBareDealGrantBeforeStart() {
        var activity=SeckillTestFixture.create(em,now.plusSeconds(60),now.plusSeconds(7200),3,1);
        var grant=grants.issue(user,"sk-order-session",activity.getDeal().getPublicId(),3990,"deal-v1");
        // 即使旧接口拿到套餐级凭证，也必须先识别秒杀窗口，不能沿旧路径直接创建订单。
        assertThatThrownBy(()->orders.create(user,"sk-order-session",activity.getDeal().getPublicId(),1,grant.getGrantId(),"legacy-request"))
                .isInstanceOfSatisfying(ToolBoundaryException.class,e->assertThat(e.getCode()).isEqualTo("NOT_STARTED"));
    }

    /** 精确覆盖支付截止前1毫秒、等号和后1毫秒；必须同时核对付款事实和库存占用，防止只验证异常文本。 */
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void paymentDeadlineMillisecondBoundary(long offsetMillis) {
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(7200),3,1);
        var result=createSuccessfully(quote(activity),"deadline-request");
        String orderId=(String)result.get("orderId");
        // 权威时间仅用于确定边界；订单创建时写入的300秒截止不在测试中修改。
        when(time.now()).thenReturn(now.plusSeconds(300).plusMillis(offsetMillis));
        if(offsetMillis<0){
            assertThat(orders.pay(user,orderId,"payment-key").get("status")).isEqualTo("PAID");
            assertThat(voucher(orderId).getOrder().getPaidTotalCents()).isEqualTo(3990);
        }else{
            assertThatThrownBy(()->orders.pay(user,orderId,"payment-key"))
                    .isInstanceOfSatisfying(ToolBoundaryException.class,e->assertThat(e.getCode()).isEqualTo("PAYMENT_EXPIRED"));
            assertThat(voucher(orderId).getOrder().getPaidTotalCents()).isNull();
            assertThat(voucher(orderId).getOrder().getPaidAt()).isNull();
        }
        // 支付成功与到期拒绝均不能直接归还库存；超时释放由独立事务执行，避免付款拒绝路径重复释放。
        em.flush();em.refresh(activity.getDeal());
        assertThat(activity.getDeal().getInventory()).isEqualTo(2);
    }

    /** 停止活动只禁止新购：已建单仍可按原期限付款，未支付订单到期释放后也不能恢复活动购买资格。 */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void stoppedActivityKeepsExistingOrderDeadline(boolean payBeforeDeadline){
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(7200),3,1);
        var result=createSuccessfully(quote(activity),"stopped-order");String orderId=(String)result.get("orderId");
        // 使用隔离夹具模拟受控停止输入，本期不添加商家管理接口。
        em.createNativeQuery("update seckill_activities set status='STOPPED',activity_version=activity_version+1 where id=:id")
                .setParameter("id",activity.getId()).executeUpdate();em.refresh(activity);
        if(payBeforeDeadline){
            when(time.now()).thenReturn(now.plusSeconds(299));
            assertThat(orders.pay(user,orderId,"stopped-payment").get("status")).isEqualTo("PAID");
        }else{
            when(time.now()).thenReturn(now.plusSeconds(300));
            assertThat(expiry.expire(orderId)).isTrue();
            assertThat(voucher(orderId).getOrder().getStatus().name()).isEqualTo("CLOSED_TIMEOUT");
            assertThat(voucher(orderId).getOrder().getPaidTotalCents()).isNull();
        }
        // 已付保留扣减、超时归还，但两种情况都必须拒绝新报价；库存恢复不等于活动重新开放。
        em.flush();em.refresh(activity.getDeal());
        assertThat(activity.getDeal().getInventory()).isEqualTo(payBeforeDeadline?2:3);
        assertThatThrownBy(()->quote(activity)).isInstanceOfSatisfying(ToolBoundaryException.class,
                e->assertThat(e.getCode()).isEqualTo("STOPPED"));
    }

    /** 已合法建立的订单保留自己的支付期限，活动结束不能提前取消其付款权利。 */
    @Test void alreadyCreatedOrderCanBePaidAfterActivityEnds() {
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(10),3,1);
        var result=createSuccessfully(quote(activity),"ending-request");
        when(time.now()).thenReturn(now.plusSeconds(60));
        assertThat(orders.pay(user,(String)result.get("orderId"),"payment-key").get("status")).isEqualTo("PAID");
    }

    @ParameterizedTest @ValueSource(strings={"token","redeem"})
    void unpaidSeckillVoucherCannotBeUsedThroughExistingEntrypoints(String entry){
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),3,1);
        var result=createSuccessfully(quote(activity),"unpaid-voucher");var voucher=voucher((String)result.get("orderId"));
        // 本期不新增核销功能，仅堵住已存在入口绕过秒杀付款事实的路径。
        if("token".equals(entry))assertThatThrownBy(()->tokens.issue(voucher)).isInstanceOf(com.fandazi.feature.DomainException.class);
        else assertThatThrownBy(()->voucher.redeem(activity.getDeal().getStore().getPublicId())).isInstanceOf(IllegalStateException.class);
    }

    /** 待支付、超时关闭和未到使用日期的券均不得投影为可使用，防止客户端错误开放履约。 */
    @Test void pendingClosedAndPaidFutureVouchersDoNotAppearUsable(){
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),3,1);
        var result=createSuccessfully(quote(activity),"voucher-state");String id=(String)result.get("orderId");
        var voucher=voucher(id);
        var pending=com.fandazi.feature.voucher.VoucherSummary.from(voucher,false);
        assertThat(pending.get("status")).isEqualTo("PENDING_PAYMENT");assertThat(pending.get("canUse")).isEqualTo(false);
        // 券自然日使用期限独立于支付截止；关闭后不可使用，不能继续沿UNUSED文案展示。
        assertThat(java.time.Instant.parse((String)pending.get("expiresAt"))).isAfter(now.plusSeconds(300));
        when(time.now()).thenReturn(now.plusSeconds(300));assertThat(expiry.expire(id)).isTrue();
        var closed=com.fandazi.feature.voucher.VoucherSummary.from(voucher,false);
        assertThat(closed.get("status")).isEqualTo("CLOSED_TIMEOUT");assertThat(closed.get("canUse")).isEqualTo(false);
    }

    /** 发券须保存独立使用起始日期；付款成功不能提前开放未来日期的券。 */
    @Test void voucherUsageStartIsCapturedAndPaidFutureVoucherWaitsForItsDate(){
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),3,1);
        var expectedDate=activity.getDeal().getValidFrom();
        var result=createSuccessfully(quote(activity),"voucher-date");String id=(String)result.get("orderId");
        orders.pay(user,id,"pay-future");
        // 构造受控目录输入变化证明历史券保留原使用起日；生产没有商家修改端点。
        em.createQuery("update DealPackageEntity d set d.validFrom=:date where d.id=:id").setParameter("date",expectedDate.plusDays(5))
                .setParameter("id",activity.getDeal().getId()).executeUpdate();em.refresh(activity.getDeal());
        var summary=com.fandazi.feature.voucher.VoucherSummary.from(voucher(id),false);
        assertThat(summary.get("validFrom")).isEqualTo(expectedDate.toString());
        assertThat(summary.get("status")).isEqualTo("NOT_YET_VALID");assertThat(summary.get("canUse")).isEqualTo(false);
    }

    /** 已支付且处于使用日期内的券继续沿用原令牌能力，防止秒杀日期防护破坏正常履约。 */
    @Test void paidVoucherWithinItsUsageDatesCanStillIssueItsExistingToken(){
        var activity=SeckillTestFixture.create(em,now.minusSeconds(1),now.plusSeconds(3600),3,1);
        var today=java.time.LocalDate.now(com.fandazi.core.time.BusinessTime.ZONE);
        em.createQuery("update DealPackageEntity d set d.validFrom=:date where d.id=:id").setParameter("date",today)
                .setParameter("id",activity.getDeal().getId()).executeUpdate();em.refresh(activity.getDeal());
        var result=createSuccessfully(quote(activity),"usable-voucher");String id=(String)result.get("orderId");orders.pay(user,id,"paid-usable");
        var voucher=voucher(id);var summary=com.fandazi.feature.voucher.VoucherSummary.from(voucher,false);
        // 正例防止通过全面封锁券权益来满足未付款负例；令牌值不输出到测试报告。
        assertThat(summary.get("canUse")).isEqualTo(true);assertThat(summary.get("status")).isEqualTo("UNUSED");
        assertThat(tokens.issue(voucher).isBlank()).isFalse();
    }

    /** 读取本次订单的券聚合，保持与实际订单创建使用同一持久对象。 */
    private com.fandazi.feature.voucher.VoucherEntity voucher(String order){
        return em.createQuery("select o from GroupBuyOrderEntity o where o.publicId=:id",com.fandazi.feature.grouporder.GroupBuyOrderEntity.class)
                .setParameter("id",order).getSingleResult().getVouchers().get(0);
    }

    /** 调用真实报价服务，数量始终由后端快照传递，不在测试中手工伪造金额。 */
    private Map<String,Object> quote(SeckillActivityEntity activity) {
        return quotes.createForPage(user,"sk-order-session",activity.getDeal().getPublicId(),1);
    }
    /** 调用既有订单入口以覆盖页面和Agent共用路径，报价查询允许读取已消费记录供幂等重放。 */
    private Map<String,Object> create(Map<String,Object> quote,String key) {
        return orders.createFromQuote(user,"sk-order-session",quotes.owned(user,(String)quote.get("quoteId")),
                (String)quote.get("confirmationGrant"),key);
    }
    /** 将缺失创建行为明确记为指定断言失败，不能让环境或编译错误冒充有效红灯。 */
    private Map<String,Object> createSuccessfully(Map<String,Object> quote,String key) {
        var result=new AtomicReference<Map<String,Object>>();
        assertThatCode(()->result.set(create(quote,key))).as("合法活动应成功创建待支付秒杀订单").doesNotThrowAnyException();
        return result.get();
    }
}
