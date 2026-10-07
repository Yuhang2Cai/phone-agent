// 真实错误页的开发故障测试：验证一次性、用户隔离、过期及生产环境禁用，禁止伪造订单数据。
package com.fandazi.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;

/** 该夹具只能让真实订单读取失败一次，不能创建、删除或修改任何业务行。 */
class DevelopmentOrderFailureTest {
    /** 指定账号的下一次读取返回 503，其他账号与后续重试不受影响。 */
    @Test
    void failsOnceForOnlyTheAuthenticatedOwner() {
        var fault = new DevelopmentOrderFailure();
        fault.arm(17L);
        fault.beforeList(18L);
        assertThatThrownBy(() -> fault.beforeList(17L)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(503));
        fault.beforeList(17L);
    }

    /** 未到下一次请求而过期时不再失败，防止很久以后普通使用被旧验证状态干扰。 */
    @Test
    void expiredArmingDoesNotFail() {
        var fault = new DevelopmentOrderFailure(Duration.ZERO);
        fault.arm(17L);
        fault.beforeList(17L);
    }

    /** 必须同时满足 development 与显式开关，叠加 prod/production 时仍不得注册。 */
    @Test
    void registrationRequiresDevelopmentAndOptIn() {
        for (String profile : new String[]{"test", "prod", "production", "development,prod", "development,production"}) {
            new ApplicationContextRunner().withUserConfiguration(DevelopmentOrderFailure.class)
                    .withPropertyValues("spring.profiles.active=" + profile, "task8.failures.enabled=true")
                    .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOrderFailure.class));
        }
        new ApplicationContextRunner().withUserConfiguration(DevelopmentOrderFailure.class)
                .withPropertyValues("spring.profiles.active=development", "task8.failures.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOrderFailure.class));
        new ApplicationContextRunner().withUserConfiguration(DevelopmentOrderFailure.class)
                .withPropertyValues("spring.profiles.active=development", "task8.failures.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevelopmentOrderFailure.class));
    }
}
