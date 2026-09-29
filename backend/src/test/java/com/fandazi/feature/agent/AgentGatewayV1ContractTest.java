// Agent V1 运行协议测试：锁定手机后端向独立 Agent 发送的关键字段。
package com.fandazi.feature.agent;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证请求模型能够稳定生成 V1 协议所需的链路和上下文字段。 */
class AgentGatewayV1ContractTest {
    @Test
    void requestExposesVersionedRunIdentityAndTrustedContext() {
        // 这些字段必须由 Spring 网关生成或传递，不能依赖 Agent 模型自行补全。
        AgentRunRequest request = new AgentRunRequest("42", "session-1", "推荐附近套餐", "recommend", "trace-1");

        assertThat(request.schemaVersion()).isEqualTo("1.0");
        assertThat(request.runId()).isEqualTo("run-trace-1");
        assertThat(request.context()).containsEntry("mode", "recommend");
    }

    @Test
    void contextDoesNotExposeMutableBusinessFactsByDefault() {
        // 默认上下文只携带路由模式，门店、订单和金额必须由 Spring 工具实时查询。
        AgentRunRequest request = new AgentRunRequest("42", "session-1", "查订单", "order", "trace-2");

        assertThat(request.context()).isEqualTo(Map.of("mode", "order"));
    }
}
