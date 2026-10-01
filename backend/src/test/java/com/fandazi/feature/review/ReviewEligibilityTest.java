// 团购评价资格测试：按订单中的核销券判定，防止未消费评价和重复评价。
package com.fandazi.feature.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 评价资格由后端券状态和订单唯一约束共同保证。 */
// 独立种子库确保全量回归执行顺序不会改变评价资格前提。
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:task8-review;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@Transactional
class ReviewEligibilityTest {
    // 测试仍以确定性别名选择场景，实际 API 和断言使用数据库随机 UUID，防止开放旧枚举入口。
    @Autowired private com.fandazi.core.identity.PublicIdAliases publicIds;
    @Autowired private ReviewService reviews;
    @Autowired private GroupBuyOrderService orders;
    @Autowired private UserRepository users;
    @Autowired private com.fandazi.feature.grouporder.GroupBuyOrderRepository entities;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    /** 只有 REDEEMED 标签但没有核销时间时，后端不能认为已消费并允许评价。 */
    @Test
    void redeemedStatusWithoutRedemptionFactCannotBeReviewed() {
        Long owner = users.findByUsername("seed_alice").orElseThrow().getId();
        String orderId = publicIds.require("order", "group-order-used");
        var voucher = entities.findByPublicId(orderId).orElseThrow().getVouchers().get(0);
        entityManager.createNativeQuery("UPDATE vouchers SET redeemed_at=NULL WHERE id=:id")
                .setParameter("id", voucher.getId()).executeUpdate();
        entityManager.clear();
        assertThat(orders.detail(owner, orderId).get("canReview")).isEqualTo(false);
        assertThatThrownBy(() -> reviews.create(owner, orderId, 4, "没有核销事实的测试评价"))
                .isInstanceOf(DomainException.class).hasMessageContaining("核销后才能评价");
    }

    /** 评价与读取资格都绑定当前用户，另一账号持有订单 UUID 也不能发布评价。 */
    @Test
    void anotherOwnerCannotReview() {
        var stranger = users.save(new com.fandazi.feature.auth.UserEntity("review-stranger-" + java.util.UUID.randomUUID(),
                "review-stranger@example.invalid", "不可登录测试哈希"));
        assertThatThrownBy(() -> reviews.create(stranger.getId(), publicIds.require("order", "group-order-used"), 4, "越权评价"))
                .isInstanceOf(DomainException.class).hasMessageContaining("团购订单不存在");
    }

    /** 未核销订单不可评价，即使它已支付。 */
    @Test
    void orderWithoutRedeemedVoucherCannotBeReviewed() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        assertThatThrownBy(() -> reviews.create(userId, publicIds.require("order", "group-order-paid"), 5, "很好吃"))
                .isInstanceOf(DomainException.class).hasMessageContaining("核销后才能评价");
    }

    /** 部分核销已经具备体验反馈资格，但同一订单只能评价一次。 */
    @Test
    void partiallyUsedOrderCanBeReviewedOnlyOnce() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        // 订单详情对评价入口给出服务端资格，端上不能只凭券状态猜测是否已经评价。
        assertThat(orders.detail(userId, publicIds.require("order", "group-order-paid")).get("canReview")).isEqualTo(false);
        assertThat(orders.detail(userId, publicIds.require("order", "group-order-partial")).get("canReview")).isEqualTo(true);
        var created = reviews.create(userId, publicIds.require("order", "group-order-partial"), 4, "菜品不错");
        assertThat(created.get("orderId")).isEqualTo(publicIds.require("order", "group-order-partial"));
        assertThat(orders.detail(userId, publicIds.require("order", "group-order-partial")).get("canReview")).isEqualTo(false);
        assertThatThrownBy(() -> reviews.create(userId, publicIds.require("order", "group-order-partial"), 5, "重复评价"))
                .isInstanceOf(DomainException.class).hasMessageContaining("已评价");
    }
}
