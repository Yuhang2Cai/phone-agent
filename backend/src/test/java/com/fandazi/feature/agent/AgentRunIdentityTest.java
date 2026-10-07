// 任务标识回归：通过真实 Spring 会话持久化验证暂停恢复与新任务分配。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** 会话仓储是真实测试数据库，外部 Agent 仅作为可控业务边界替身。 */
@SpringBootTest
@Transactional
class AgentRunIdentityTest {
    @Autowired private AgentGatewayService gateway;
    @Autowired private UserRepository users;
    @MockBean private AgentServiceClient client;

    @Test
    void inputAndConfirmationResumeSameRunWithNewTrace() {
        // 三次输入依次选券、填原因、确认；属于同一任务，不应每轮重建 runId。
        UserEntity user = users.save(new UserEntity("run-identity", "run-identity@example.invalid", "unused-test-hash"));
        when(client.run(any())).thenReturn(
                new AgentResult(AgentResult.Status.NEEDS_INPUT, "请选择券", "select_voucher", Map.of(), List.of(), null),
                new AgentResult(AgentResult.Status.AWAITING_CONFIRMATION, "请确认", "confirm_refund", Map.of(), List.of(), null),
                AgentResult.completed("已申请", "completed", List.of()));
        gateway.handle(user.getId(), "run-identity-session", "申请退款", AgentMode.CONFIRM);
        gateway.handle(user.getId(), "run-identity-session", "测试原因", AgentMode.CONFIRM);
        gateway.handle(user.getId(), "run-identity-session", "确认退款", AgentMode.CONFIRM);
        ArgumentCaptor<AgentRunRequest> captured = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(client, times(3)).run(captured.capture());
        assertThat(captured.getAllValues().stream().map(AgentRunRequest::runId).distinct().count()).isEqualTo(1);
        assertThat(captured.getAllValues().stream().map(AgentRunRequest::traceId).distinct().count()).isEqualTo(3);
    }

    @Test
    void nextGoalAfterCompletionGetsAnotherRun() {
        // 会话仍相同但目标已结束，第二个任务不能复用上一任务的幂等和确认上下文。
        UserEntity user = users.save(new UserEntity("new-run", "new-run@example.invalid", "unused-test-hash"));
        when(client.run(any())).thenReturn(AgentResult.completed("已完成", "completed", List.of()));
        gateway.handle(user.getId(), "new-run-session", "查询规则", AgentMode.CONFIRM);
        gateway.handle(user.getId(), "new-run-session", "查询订单", AgentMode.CONFIRM);
        ArgumentCaptor<AgentRunRequest> captured = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(client, times(2)).run(captured.capture());
        assertThat(captured.getAllValues().get(0).runId()).isNotEqualTo(captured.getAllValues().get(1).runId());
    }

    @Test
    void delayedOldResponseCannotOverwriteNewWaitingTask() {
        // 在旧调用尚未结束时启动下一请求，模拟同一会话的交错响应；旧终态不能改写新任务。
        UserEntity user = users.save(new UserEntity("late-run", "late-run@example.invalid", "unused-test-hash"));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.run(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                gateway.handle(user.getId(), "late-run-session", "申请退款", AgentMode.CONFIRM);
                return AgentResult.completed("旧请求结束", "completed", List.of());
            }
            return new AgentResult(AgentResult.Status.NEEDS_INPUT, "请选择券", "select_voucher", Map.of(), List.of(), null);
        });
        gateway.handle(user.getId(), "late-run-session", "查询规则", AgentMode.CONFIRM);
        gateway.handle(user.getId(), "late-run-session", "补充券信息", AgentMode.CONFIRM);
        ArgumentCaptor<AgentRunRequest> captured = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(client, times(3)).run(captured.capture());
        assertThat(captured.getAllValues().get(1).runId()).isEqualTo(captured.getAllValues().get(2).runId());
    }
}
