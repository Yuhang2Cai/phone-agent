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
        "spring.datasource.url=jdbc:h2:mem:task8-review;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@Transactional
class ReviewEligibilityTest {
    @Autowired private ReviewService reviews;
    @Autowired private GroupBuyOrderService orders;
    @Autowired private UserRepository users;

    /** 未核销订单不可评价，即使它已支付。 */
    @Test
    void orderWithoutRedeemedVoucherCannotBeReviewed() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        assertThatThrownBy(() -> reviews.create(userId, "group-order-paid", 5, "很好吃"))
                .isInstanceOf(DomainException.class).hasMessageContaining("核销后才能评价");
    }

    /** 部分核销已经具备体验反馈资格，但同一订单只能评价一次。 */
    @Test
    void partiallyUsedOrderCanBeReviewedOnlyOnce() {
        Long userId = users.findByUsername("seed_alice").orElseThrow().getId();
        // 订单详情对评价入口给出服务端资格，端上不能只凭券状态猜测是否已经评价。
        assertThat(orders.detail(userId, "group-order-paid").get("canReview")).isEqualTo(false);
        assertThat(orders.detail(userId, "group-order-partial").get("canReview")).isEqualTo(true);
        var created = reviews.create(userId, "group-order-partial", 4, "菜品不错");
        assertThat(created.get("orderId")).isEqualTo("group-order-partial");
        assertThat(orders.detail(userId, "group-order-partial").get("canReview")).isEqualTo(false);
        assertThatThrownBy(() -> reviews.create(userId, "group-order-partial", 5, "重复评价"))
                .isInstanceOf(DomainException.class).hasMessageContaining("已评价");
    }
}
