// 团购购买模块测试：验证报价快照、确认凭证和套餐版本变化之间的安全边界。
package com.fandazi.feature.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证购买流程不会使用过期报价或旧套餐版本完成写操作。
 * 该测试同时约束确认凭证必须绑定资源版本，防止用户确认后价格/库存已变化仍然下单。
 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true"})
@ActiveProfiles("test")
class PurchaseConfirmationTest {
    @Autowired private ConfirmationGrantService confirmationGrants;
    @Autowired private UserRepository users;
    @Autowired private PurchaseQuoteService quotes;
    @Autowired private com.fandazi.feature.catalog.DealPackageRepository deals;
    @Autowired private com.fandazi.feature.catalog.StoreRepository stores;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void displayedDiscountReconcilesWithPayableAmount() {
        // 商品总价必须减去活动优惠后等于实付；防止页面展示已优惠价格后再次减同一折扣。
        Long owner = users.save(new UserEntity("purchase-reconcile", "purchase-reconcile@example.com", "hash")).getId();
        // 门店与套餐均由本用例创建，不读取共享种子；其他测试清空门店也不能影响金额断言。
        // merchant 是可空兼容关联，显式不依赖历史商家；门店、套餐、报价和授权随本事务结束回滚。
        var store = stores.save(new com.fandazi.feature.catalog.StoreEntity(java.util.UUID.randomUUID().toString(),
                "金额测试品牌", "金额守恒隔离门店", "task6-h2", "仅用于离线金额测试", "00000000000", "测试",
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 8800, true, "00:00-23:59", null));
        var today = java.time.LocalDate.now();
        var deal = deals.save(new com.fandazi.feature.catalog.DealPackageEntity(java.util.UUID.randomUUID().toString(),
                store, "金额守恒隔离套餐", "仅用于离线金额测试", 8800, 10800, 5,
                today, today.plusDays(30), true, 2, 1));
        var quote = quotes.createForAgent(owner, "reconcile-session", deal.getPublicId(), 2);
        assertThat((Integer) quote.get("subtotalCents") - (Integer) quote.get("discountCents"))
                .isEqualTo(quote.get("totalCents"));
    }

    @Test
    void expiredGrantCannotBeConsumed() {
        // 先创建一个已经过期的凭证，消费时必须被拒绝而不能进入订单写入阶段。
        Long userId = users.save(new UserEntity("purchase-test-expired", "purchase-test-expired@example.com", "hash")).getId();
        String grantId = confirmationGrants.issueForTest(userId, "session-test", "deal-001-1", 5650, "deal-v1", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> confirmationGrants.consume(userId, "session-test", grantId, "deal-001-1", "deal-v1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("确认凭证已过期");
    }

    @Test
    void changedDealVersionInvalidatesGrant() {
        // 版本不一致表示报价已失效，不能依靠旧确认凭证绕过重新报价。
        Long userId = users.save(new UserEntity("purchase-test-version", "purchase-test-version@example.com", "hash")).getId();
        String grantId = confirmationGrants.issueForTest(userId, "session-test", "deal-001-1", 5650, "deal-v3", Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> confirmationGrants.consume(userId, "session-test", grantId, "deal-001-1", "deal-v4"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("资源已变化");
    }
}
