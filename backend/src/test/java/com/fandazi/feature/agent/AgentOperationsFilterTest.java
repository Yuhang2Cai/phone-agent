package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import jakarta.servlet.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Servlet过滤调用链必须绑定应用作用域；异常、状态和下一请求不被观测改变。 */
class AgentOperationsFilterTest {
    @TempDir Path root;

    @Test void actualServletChainBindsAndAlwaysRestoresScope() throws Exception {
        // 明确缺失过滤器的红测，不能把未接生产入口的工厂测试当成完成接线。
        Class<?> cls=assertDoesNotThrow(()->Class.forName("com.fandazi.feature.agent.AgentOperationsFilter"),
            "缺少Servlet运维作用域接入");
        var runtime=new AgentOperationsRuntime(new MockEnvironment()
            .withProperty("AG07_OPERATIONS_ENABLED","true").withProperty("AG07_OPERATIONS_ROOT",root.toString()));
        Filter filter=(Filter)cls.getConstructor(AgentOperationsRuntime.class).newInstance(runtime);
        runtime.start();
        var expected=new ServletException("原请求失败");
        try {
            assertSame(expected,assertThrows(ServletException.class,()->filter.doFilter(
                new MockHttpServletRequest(),new MockHttpServletResponse(),(request,response)->{
                    assertSame(runtime,AgentOperationsRuntime.current());
                    assertEquals(7,AgentStageMetrics.measure("spring.history","random-request",()->7));
                    throw expected;
                })));
            assertNull(AgentOperationsRuntime.current());
        } finally {runtime.close();}
        assertEquals(1L,runtime.snapshot().get("written"));
        assertEquals(false,runtime.snapshot().get("worker_alive"));
    }
}
