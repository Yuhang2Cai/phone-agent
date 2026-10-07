// Task1 公开边界独立验收：内部准备工具可以返回凭证，公开事件与 SSE DTO 必须隔离该凭证。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.refund.RefundService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实执行工具分发、H2 事件持久化和公开 DTO 转换；仅资源准备服务与可信会话使用替身。 */
@SpringBootTest
@Transactional
class Task1PublicToolAuditAcceptanceTest {
    @Autowired private AgentToolService tools;
    @Autowired private AgentEventService events;
    @Autowired private ObjectMapper mapper;
    @MockBean private AgentSessionService sessions;
    @MockBean private RefundService refunds;

    @Test
    void preparationGrantIsInternalAndAbsentFromPublicEventHistory() throws Exception {
        // 先设置隔离身份与准备结果，明确区分内部工具返回和 Android 可订阅的公开事件。
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(42L);
        AgentSession session = new AgentSession(user, "acceptance-public-session", AgentMode.CONFIRM, "acceptance-trace");
        when(sessions.trustedForUpdate("acceptance-public-session")).thenReturn(session);
        when(refunds.prepare(42L, "acceptance-public-session", "acceptance-voucher"))
                .thenReturn(Map.of("voucherId", "acceptance-voucher", "confirmationGrant", "acceptance-only-reference"));
        ToolResult result = tools.execute("prepare_refund", new ToolRequest("42", "acceptance-public-session",
                "acceptance-trace", "acceptance-idempotency", null, Map.of("voucherId", "acceptance-voucher")));
        assertThat(result.data()).containsEntry("confirmationGrantId", "acceptance-only-reference");

        // 实际查询持久化事件，并使用 SSE 正在使用的 EventResponse；任何公开凭证字段均为验收失败。
        var history = events.after(42L, "acceptance-public-session", null);
        assertThat(history).hasSize(2);
        for (AgentEventEntity item : history) {
            String publicJson = mapper.writeValueAsString(AgentGatewayController.EventResponse.from(item));
            assertThat(publicJson).doesNotContain("confirmationGrantId", "confirmationGrant", "grantId",
                    "acceptance-only-reference");
        }
    }
}
