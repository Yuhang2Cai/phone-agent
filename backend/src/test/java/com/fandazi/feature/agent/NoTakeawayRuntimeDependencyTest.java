// 默认运行边界测试：旧外卖交易与工具不能进入团购容器，保留实体不等于启用旧服务。
package com.fandazi.feature.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** 使用真实默认Spring上下文和安全链，防止只删除UI入口却仍注册旧交易接口。 */
@SpringBootTest(properties={"seed.enabled=false", "spring.datasource.url=jdbc:h2:mem:task11-runtime;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class NoTakeawayRuntimeDependencyTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc http;

    /** 默认容器不能创建旧服务；菜单目录与新工具桥仍必须存在。 */
    @Test void excludesOldTransactionsButKeepsRealGroupBuying() {
        // 按类型而非Bean名称核对，防止改名或别名绕过退役边界。
        for (Class<?> type : List.of(com.fandazi.feature.address.AddressService.class,
                com.fandazi.feature.cart.CartService.class, com.fandazi.feature.order.OrderService.class,
                com.fandazi.feature.payment.PaymentService.class, com.fandazi.feature.delivery.DeliveryService.class,
                com.fandazi.feature.automation.AutomationPolicyService.class, AgentToolService.class, InternalToolController.class)) {
            assertThat(context.getBeansOfType(type)).as(type.getSimpleName()).isEmpty();
        }
        assertThat(context.getBeansOfType(AgentV1ToolBridge.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.fandazi.feature.catalog.StoreService.class)).hasSize(1);
    }

    /** 已登录也无法访问旧交易，公开团购浏览仍走真实控制器而不是静态响应。 */
    @Test void deniesOldEndpointsWithoutBlockingGroupBuying() throws Exception {
        var owner = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(42L,null,List.of()));
        for (String path : List.of("/api/addresses", "/api/cart", "/api/orders", "/api/automation"))
            http.perform(get(path).with(owner)).andExpect(status().isForbidden());
        http.perform(post("/internal/tools/create_order").with(owner).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/stores/cities")).andExpect(status().isOk());
    }
}
