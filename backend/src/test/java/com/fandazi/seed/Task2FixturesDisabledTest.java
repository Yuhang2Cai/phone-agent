// 开关边界测试：开发 profile 本身不能自动制造订单，必须显式启用 Task2 夹具。
package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;
import com.fandazi.feature.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** 独立数据库只装载历史基线用户，明确关闭开关后不产生七个开发账号。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true", "task2.fixtures.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:task2-disabled-fixtures;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("development")
class Task2FixturesDisabledTest {
    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    /** development 必须配合 enabled=true，普通开发启动保持原有业务数据。 */
    @Test void disabledSwitchDoesNotPrepareFixtures() {
        assertThat(context.containsBean("task2DevelopmentFixtures")).isFalse();
        assertThat(users.count()).isEqualTo(1);
    }
}
