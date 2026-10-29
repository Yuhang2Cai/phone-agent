// 秒杀公开目录契约：通过实际Spring路由与持久化验证预告可见、无缓存和认证边界。
package com.fandazi.feature.seckill;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用独立H2完成定向合同检查；真实MySQL冒烟和并发仍必须另外执行。 */
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seckill-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillCatalogTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;

    @Test
    void futureVoucherIsVisibleBeforeItsUsageDateAndReadsDoNotReserveStock() throws Exception {
        // 券明天才可使用，但今晚的预告必须可见；读取不得隐式生成订单或占用库存。
        var activity=new TransactionTemplate(transactions).execute(status->SeckillTestFixture.create(em,
                Instant.now().plusSeconds(7200),Instant.now().plusSeconds(14400),100,1));
        mvc.perform(get("/api/stores/"+activity.getDeal().getStore().getPublicId()+"/seckill-activities"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$[0].activityId").value(activity.getPublicId()))
                .andExpect(jsonPath("$[0].state").value("UPCOMING"))
                .andExpect(jsonPath("$[0].availableStock").value(100))
                .andExpect(jsonPath("$[0].deal.priceCents").value(3990));
        mvc.perform(get("/api/seckill-activities/"+activity.getPublicId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serverTime").exists())
                .andExpect(jsonPath("$.deal.usageRules").value("午市堂食"));
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            assertThat(em.find(com.fandazi.feature.catalog.DealPackageEntity.class,activity.getDeal().getId()).getInventory()).isEqualTo(100);
            assertThat(em.createQuery("select count(o) from GroupBuyOrderEntity o",Long.class).getSingleResult()).isZero();
        });
    }

    @Test
    void invalidFixtureRollsBackItsStoreAndDeal() {
        // 非法库存必须使整个初始化事务失败，防止测试或运维数据留下没有活动的半成品套餐。
        long before=new TransactionTemplate(transactions).execute(status->em.createQuery("select count(d) from DealPackageEntity d",Long.class).getSingleResult());
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->SeckillTestFixture.create(em,
                Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),0,1))).isInstanceOf(IllegalArgumentException.class);
        long after=new TransactionTemplate(transactions).execute(status->em.createQuery("select count(d) from DealPackageEntity d",Long.class).getSingleResult());
        assertThat(after).isEqualTo(before);
    }

    @Test
    void personalEligibilityAndOriginalRequestRemainAuthenticated() throws Exception {
        // 公开详情路径只能开放一个资源段，不能因通配过宽泄露本人资格或请求结果。
        mvc.perform(get("/api/seckill-activities/unknown/eligibility")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/seckill-purchases/requests/unknown")).andExpect(status().isUnauthorized());
    }
}
