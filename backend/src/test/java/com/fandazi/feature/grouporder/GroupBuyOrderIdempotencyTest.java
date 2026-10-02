// 团购订单模块测试：验证重复提交不会创建重复订单。
package com.fandazi.feature.grouporder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证订单幂等键的语义由数据库唯一约束和服务层共同保证。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true"})
@org.springframework.test.context.ActiveProfiles("test")
class GroupBuyOrderIdempotencyTest {
    @org.springframework.beans.factory.annotation.Autowired private GroupBuyOrderService service;
    @org.springframework.beans.factory.annotation.Autowired private com.fandazi.feature.auth.UserRepository users;
    @org.springframework.beans.factory.annotation.Autowired private com.fandazi.feature.catalog.DealPackageRepository deals;
    @org.springframework.beans.factory.annotation.Autowired private com.fandazi.feature.purchase.PurchaseQuoteService quotes;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void quotedOrderReplayReturnsOneOrderAndRejectsChangedQuantity() {
        // 使用真实持久化报价连续提交，既防止一次凭证产生两单，也防止同一键偷换数量。
        var user = users.save(new com.fandazi.feature.auth.UserEntity("task6-replay", "task6-replay@example.com", "hash"));
        var deal = deals.findAll().stream().filter(value -> value.isActive() && value.getInventory() >= 2 && value.getPurchaseLimit() >= 2).findFirst().orElseThrow();
        var prepared = quotes.createForAgent(user.getId(), "task6-session", deal.getPublicId(), 1);
        var quote = quotes.requireForCreation(user.getId(), (String) prepared.get("quoteId"));
        // 模拟 HTTP 控制器在读取事务结束后传入的脱管报价，而非依赖测试外层事务自动保存消费状态。
        entityManager.detach(quote);
        var first = service.createFromQuote(user.getId(), "task6-session", quote, (String) prepared.get("confirmationGrant"), "task6-key");
        entityManager.flush();
        assertThat(entityManager.find(com.fandazi.feature.purchase.PurchaseQuote.class, quote.getId()).getStatus()).isEqualTo("CONSUMED");
        assertThat(service.createFromQuote(user.getId(), "task6-session", quote, (String) prepared.get("confirmationGrant"), "task6-key").get("orderId"))
                .isEqualTo(first.get("orderId"));
        var changed = quotes.createForAgent(user.getId(), "task6-session", deal.getPublicId(), 2);
        assertThatThrownBy(() -> service.createFromQuote(user.getId(), "task6-session",
                quotes.requireForCreation(user.getId(), (String) changed.get("quoteId")), (String) changed.get("confirmationGrant"), "task6-key"))
                .hasMessageContaining("幂等");
        // 支付重复请求不能再生成券或改变实付快照，券数量应保持一份一券。
        var paid = service.pay(user.getId(), (String) first.get("orderId"), "task6-pay");
        assertThat(service.pay(user.getId(), (String) first.get("orderId"), "task6-pay")).isEqualTo(paid);
        assertThat(paid.get("voucherCount")).isEqualTo(1);
    }
}
