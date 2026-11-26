// 生产边界测试：即使误开夹具开关且包含 development，production profile 仍阻止伪造状态。
package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** 使用独立 H2 验证真实容器未注册夹具 Bean，不创建用户、订单或运行凭据文件。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true", "task2.fixtures.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:task2-prod-guard;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"development", "production"})
class Task2FixturesProductionGuardTest {
    @Autowired private ApplicationContext context;
    /** 显式生产配置压过开发配置，启用开关不能在生产业务入口制造核销或退款。 */
    @Test void productionCannotCreateDevelopmentFixtures() {
        assertThat(context.containsBean("task2DevelopmentFixtures")).isFalse();
    }
}
