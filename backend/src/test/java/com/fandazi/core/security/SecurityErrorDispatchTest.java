// 安全分发回归：仅框架错误分发保留原状态，普通私有请求和停用入口仍须认证或拒绝。
package com.fandazi.core.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 不使用业务库或故障控制数据，只验证真实Spring安全链的DispatcherType边界。 */
@WebMvcTest(controllers = SecurityErrorDispatchTest.ErrorProbeController.class)
// 显式限定切片来源，避免应用级publicId转换器拉入无关JPA仓库；测试不启动种子或业务库。
@ContextConfiguration(classes = SecurityErrorDispatchTest.ErrorProbeController.class)
@Import({SecurityConfiguration.class, JwtAuthenticationFilter.class})
class SecurityErrorDispatchTest {
    @Autowired private MockMvc mvc;
    // 模拟JWT解析仅用于已停用入口拒绝测试，生产签名链由真实HTTP副链另行验证。
    @MockBean private JwtService jwt;

    /** 已鉴权请求的框架ERROR二次分发应保留503，不能改成401误使App清除登录。 */
    @Test
    void frameworkErrorKeepsOriginalStatus() throws Exception {
        mvc.perform(get("/api/private/error-probe").with(request -> {
                    // 模拟Servlet容器二次错误分发，不通过HTTP参数授予用户额外权限。
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isServiceUnavailable());
    }

    /** 普通用户直接访问同一路径不能借ERROR放行，仍返回401。 */
    @Test
    void directErrorProbeRemainsProtected() throws Exception {
        mvc.perform(get("/api/private/error-probe")).andExpect(status().isUnauthorized());
    }

    /** 私有业务入口继续要求身份，不能因为修复错误分发扩大匿名权限。 */
    @Test
    void privateOrdersRemainProtected() throws Exception {
        mvc.perform(get("/api/group-orders")).andExpect(status().isUnauthorized());
    }

    /** 已停用接口即使携带有效用户身份也拒绝，不恢复旧订单/外卖路径。 */
    @Test
    void retiredOrdersRemainDeniedForAuthenticatedUser() throws Exception {
        when(jwt.parseUserId("test-only-token")).thenReturn(17L);
        mvc.perform(get("/api/orders").header("Authorization", "Bearer test-only-token"))
                .andExpect(status().isForbidden());
    }

    /** 仅测试上下文内的错误目标，不被正式应用组件扫描，也不写数据库。 */
    @RestController
    static class ErrorProbeController {
        /** 固定返回503以隔离安全分发行为，不模拟任何业务结果。 */
        @GetMapping("/api/private/error-probe")
        @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
        public void failed() { }
    }
}
