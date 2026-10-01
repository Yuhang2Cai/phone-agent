// 工具字段边界回归：确认凭证与 FAQ 问题采用权威命名，不把旧字段散落到新工作流。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.support.FaqService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 实际执行工具分发器，替身只控制领域查询和会话归属，不替代字段适配逻辑。 */
@SpringBootTest
class AgentToolFieldBoundaryTest {
    @Autowired private AgentToolService tools;
    @MockBean private AgentSessionService sessions;
    @MockBean private AgentEventService events;
    @MockBean private RefundService refunds;
    @MockBean private FaqService faqs;

    @Test
    void preparedGrantUsesCanonicalName() {
        // 领域 API 尚保留旧名时，Agent 工具出口必须集中转换，而不影响普通页面旧 DTO。
        session();
        when(refunds.prepare(42L, "field-session", "field-voucher"))
                .thenReturn(Map.of("voucherId", "field-voucher", "confirmationGrant", "fixture-grant"));
        ToolResult result = tools.execute("prepare_refund", request(Map.of("voucherId", "field-voucher")));
        assertThat(result.data()).containsEntry("confirmationGrantId", "fixture-grant").doesNotContainKey("confirmationGrant");
    }

    @Test
    void refundAcceptsCanonicalGrantInput() {
        // 权威字段必须真正传到领域服务；旧名缺失不能变成空凭证并误判确认失败。
        session();
        when(refunds.request(42L, "field-session", "field-voucher", "无法到店", "fixture-grant"))
                .thenReturn(Map.of("requestId", "field-refund", "status", "PENDING"));
        ToolResult result = tools.execute("request_refund", request(Map.of("voucherId", "field-voucher",
                "reason", "无法到店", "confirmationGrantId", "fixture-grant")));
        assertThat(result.data()).containsEntry("requestId", "field-refund");
    }

    @Test
    void faqUsesQuestionRatherThanEmptyKeyword() {
        // 契约里的 question 与旧服务 keyword 在单一适配器归一，不能悄悄查询空字符串。
        session();
        when(faqs.query("退款规则")).thenReturn(List.of(Map.of("answer", "测试规则")));
        ToolResult result = tools.execute("query_faq", request(Map.of("question", "退款规则")));
        assertThat(result.data().get("faqs")).isEqualTo(List.of(Map.of("answer", "测试规则")));
    }

    /** 准备明确确认的归属会话；用户与资源均为不写入生产数据库的隔离测试值。 */
    private void session() {
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(42L);
        AgentSession session = new AgentSession(user, "field-session", AgentMode.CONFIRM, "field-trace");
        session.confirm();
        when(sessions.trustedForUpdate("field-session")).thenReturn(session);
    }

    /** 使用旧构造器验证边界兼容，领域执行仍必须读取新契约字段。 */
    private ToolRequest request(Map<String, Object> payload) {
        return new ToolRequest("42", "field-session", "field-trace", "field-idempotency", null, payload);
    }
}
