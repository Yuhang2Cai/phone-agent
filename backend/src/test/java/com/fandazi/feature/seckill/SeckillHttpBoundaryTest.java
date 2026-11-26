// 秒杀用户HTTP合同：稳定拒绝码、本人资格与原请求查询不得泄露他人订单或触发新购买。
package com.fandazi.feature.seckill;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 使用真实控制器、安全链和业务持久化；认证身份只由测试安全上下文提供。 */
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seckill-http;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @Transactional
class SeckillHttpBoundaryTest {
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @Autowired PurchaseQuoteService quotes;
    @Autowired GroupBuyOrderService orders;
    @Autowired SeckillExpiryService expiry;
    /** 创建合成独立账号，测试不接受HTTP传入userId模拟授权。 */
    private Long user(){String id="sk-http-"+UUID.randomUUID();var user=new UserEntity(id,id+"@example.invalid","test-hash");em.persist(user);em.flush();return user.getId();}
    /** 构造与现有JWT过滤器相同类型的已认证主体。 */
    private org.springframework.test.web.servlet.request.RequestPostProcessor identity(Long user){
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,null,List.of()));
    }
    /** 未开抢报价必须返回稳定业务拒绝及禁止缓存头，不能提前生成可用购买确认。 */
    @Test void upcomingQuoteReturnsStableConflictWithoutCache() throws Exception {
        Long user=user();Instant now=Instant.now();var activity=SeckillTestFixture.create(em,now.plusSeconds(60),now.plusSeconds(3600),2,1);
        mvc.perform(post("/api/purchases/quotes").with(identity(user)).contentType("application/json")
                .content("{\"dealId\":\""+activity.getDeal().getPublicId()+"\",\"quantity\":1,\"sessionId\":\"http-session\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_STARTED"))
                .andExpect(jsonPath("$.reasonCode").value("NOT_STARTED")).andExpect(header().string("Cache-Control","no-store"));
    }
    /** 本人资格与原请求查询必须按认证主体隔离，防止其他账号获得订单事实或产生额外占用。 */
    @Test void personalQuotaAndOriginalReceiptAreScopedToAuthenticatedUser() throws Exception {
        Long owner=user(),other=user();Instant now=Instant.now();var activity=SeckillTestFixture.create(em,now.minusSeconds(60),now.plusSeconds(3600),2,1);
        var quote=quotes.createForPage(owner,"http-session",activity.getDeal().getPublicId(),1);
        var order=orders.createFromQuote(owner,"http-session",quotes.owned(owner,(String)quote.get("quoteId")),(String)quote.get("confirmationGrant"),"original-request");
        mvc.perform(get("/api/seckill-activities/"+activity.getPublicId()+"/eligibility").with(identity(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.remainingQuota").value(0)).andExpect(jsonPath("$.canPurchase").value(false))
                .andExpect(jsonPath("$.reasonCode").value("LIMIT_REACHED"));
        mvc.perform(get("/api/seckill-activities/"+activity.getPublicId()+"/eligibility").with(identity(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingQuota").value(1)).andExpect(jsonPath("$.canPurchase").value(true));
        mvc.perform(get("/api/seckill-purchases/requests/original-request").with(identity(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FOUND"))
                .andExpect(jsonPath("$.order.orderId").value(order.get("orderId")));
        mvc.perform(get("/api/seckill-purchases/requests/original-request").with(identity(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.order").doesNotExist());
        // 查询不代替重试购买，不消费授权、不产生第二个用户的订单或额度。
        assertThat(em.createQuery("select count(o) from GroupBuyOrderEntity o where o.user.id=:user",Long.class).setParameter("user",other).getSingleResult()).isZero();
    }
    /** 同一HTTP支付入口分别拒绝已到期和已关闭订单，确保旧页面不能绕过300秒截止。 */
    @Test void expiredAndClosedPaymentUseStableHttpReasons() throws Exception {
        Long owner=user();Instant now=Instant.now();var activity=SeckillTestFixture.create(em,now.minusSeconds(60),now.plusSeconds(3600),2,1);
        var quote=quotes.createForPage(owner,"http-session",activity.getDeal().getPublicId(),1);
        var order=orders.createFromQuote(owner,"http-session",quotes.owned(owner,(String)quote.get("quoteId")),(String)quote.get("confirmationGrant"),"expired-http-request");
        String orderId=(String)order.get("orderId");
        // 仅此定向用例把订单准备为已到期夹具；真实300秒验收仍须独立执行，不由此替代。
        em.createQuery("update GroupBuyOrderEntity o set o.paymentExpiresAt=:deadline,o.createdAt=:created where o.publicId=:id")
                .setParameter("deadline",now.minusSeconds(1))
                .setParameter("created",java.time.LocalDateTime.ofInstant(now.minusSeconds(301),com.fandazi.core.time.BusinessTime.ZONE))
                .setParameter("id",orderId).executeUpdate();
        mvc.perform(post("/api/group-orders/"+orderId+"/mock-pay").with(identity(owner)).contentType("application/json")
                .content("{\"idempotencyKey\":\"expired-payment\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reasonCode").value("PAYMENT_EXPIRED"));
        assertThat(expiry.expire(orderId)).isTrue();
        mvc.perform(post("/api/group-orders/"+orderId+"/mock-pay").with(identity(owner)).contentType("application/json")
                .content("{\"idempotencyKey\":\"closed-payment\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reasonCode").value("ORDER_CLOSED"));
    }
}
