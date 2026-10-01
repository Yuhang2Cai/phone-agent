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
