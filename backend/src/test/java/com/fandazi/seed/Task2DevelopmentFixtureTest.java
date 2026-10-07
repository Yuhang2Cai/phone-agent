// 开发夹具回归：使用独立 H2 和随机运行凭据验证七种隔离售后状态，不将密码写进源码。
package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.refund.RefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** 明确 development profile 和 enabled 开关才能准备状态；运行账号只落忽略目录。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true", "task2.fixtures.enabled=true",
        "task2.fixtures.output=../.runtime/task2-test-fixtures.json",
        "spring.datasource.url=jdbc:h2:mem:task2-dev-fixtures;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("development")
class Task2DevelopmentFixtureTest {
    @Autowired private ApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private GroupBuyOrderService orders;
    @Autowired private RefundService refunds;

    /** 七个账号的状态互不影响，密码随机生成且数据库只有哈希，金额和资格来自实际业务服务。 */
    @Test
    void sevenAccountsAreIsolatedAndCoverRequiredStates() throws Exception {
        assertThat(context.containsBean("task2DevelopmentFixtures")).isTrue();
        var manifest = mapper.readTree(Files.readString(Path.of("../.runtime/task2-test-fixtures.json")));
        assertThat(manifest.path("scenarios").size()).isEqualTo(7);
        Set<Long> ownerIds = new HashSet<>(); Set<String> plaintexts = new HashSet<>();
        for (var scenario : manifest.path("scenarios")) {
            var user = users.findByUsername(scenario.path("username").asText()).orElseThrow();
            String password = scenario.path("password").asText();
            assertThat(passwords.matches(password, user.getPasswordHash())).isTrue();
            assertThat(user.getPasswordHash()).isNotEqualTo(password);
            assertThat(ownerIds.add(user.getId())).isTrue(); assertThat(plaintexts.add(password)).isTrue();
            var owned = orders.list(user.getId());
            String state = scenario.path("scenario").asText();
            if (state.equals("no_orders")) { assertThat(owned).isEmpty(); continue; }
            assertThat(owned).hasSize(1);
            var order = owned.get(0);
            @SuppressWarnings("unchecked") var vouchers = (List<Map<String, Object>>) order.get("vouchers");
            // 各状态通过真实读模型断言，防止夹具只有标签但没有对应数据库事实。
            switch (state) {
                case "pending_payment" -> assertThat(vouchers).allSatisfy(voucher -> assertThat(voucher).containsEntry("amountCents", null).containsEntry("canRefund", false));
                case "refundable" -> {
                    assertThat(vouchers).allSatisfy(voucher -> assertThat(voucher.get("canRefund")).isEqualTo(true));
                    assertThat(vouchers.stream().mapToInt(voucher -> (Integer) voucher.get("amountCents")).sum()).isEqualTo(10001);
                    assertThat(order.get("imageUrl")).isNotNull();
                }
                case "refund_requested" -> {
                    assertThat(vouchers.get(0).get("canRefund")).isEqualTo(false);
                    assertThat(refunds.detail(user.getId(), scenario.path("requestId").asText())).containsEntry("status", "REQUESTED").containsEntry("amountCents", 10001);
                }
                case "reviewable" -> assertThat(order.get("canReview")).isEqualTo(true);
                case "reviewed" -> assertThat(order.get("canReview")).isEqualTo(false);
                case "no_candidates" -> {
                    assertThat(vouchers).allSatisfy(voucher -> assertThat(voucher.get("canRefund")).isEqualTo(false));
                    assertThat(order.get("canReview")).isEqualTo(false);
                    // 无可退款候选由券资格决定；Task13已补齐真实餐图，不能继续要求图片缺失。
                    String imageUrl = (String) order.get("imageUrl");
                    assertThat(imageUrl).matches("^/catalog-images/v[0-9]+/full/[a-z0-9-]+-[a-f0-9]{12}\\.jpg$");
                    assertThat(new org.springframework.core.io.ClassPathResource("static" + imageUrl).exists()).isTrue();
                }
                default -> throw new AssertionError("未知开发场景：" + state);
            }
        }
    }
}
