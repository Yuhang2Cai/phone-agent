package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AgentGatewayTest {
    @Autowired private AgentGatewayService gateway;
    @Autowired private AgentEventRepository events;
    @Autowired private UserRepository users;
    @Autowired private AgentSessionService sessions;
    @Autowired private MockMvc mockMvc;
    @MockBean private AgentServiceClient agentService;

    @Test
    void persistsAgentTraceAndDoesNotAllowAnotherUserToReuseItsSession() {
        UserEntity alice = users.save(new UserEntity("agent-alice", "agent-alice@example.com", "hash"));
        UserEntity bob = users.save(new UserEntity("agent-bob", "agent-bob@example.com", "hash"));
        when(agentService.run(any())).thenReturn(AgentResult.completed("已推荐", "recommendation_ready",
                List.of(new AgentResult.Event("tool", "正在搜索商家", "search_merchants", "ok", java.util.Map.of("count", 1)))));

        AgentResult result = gateway.handle(alice.getId(), "owned-session", "想吃盖饭", AgentMode.CONFIRM);

        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(events.findByUserIdAndSessionIdOrderByIdAsc(alice.getId(), "owned-session"))
                .extracting(AgentEventEntity::getSummary).contains("正在搜索商家", "已推荐");
        assertThatThrownBy(() -> gateway.handle(bob.getId(), "owned-session", "确认", AgentMode.CONFIRM))
                .isInstanceOf(DomainException.class).hasMessageContaining("会话");
    }

    @Test
    void mapsAgentTimeoutToTerminalRetryableResultWithoutTouchingFoodEndpoints() {
        UserEntity user = users.save(new UserEntity("agent-timeout", "agent-timeout@example.com", "hash"));
        when(agentService.run(any())).thenThrow(new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试"));

        AgentResult result = gateway.handle(user.getId(), "timeout-session", "推荐午餐", AgentMode.CONFIRM);

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.error()).contains("暂不可用");
    }

    @Test
    void replaysPersistedEventsAndKeepsTheOrdinaryMerchantPageAvailableAfterAgentFailure() throws Exception {
        UserEntity user = users.save(new UserEntity("agent-sse", "agent-sse@example.com", "hash"));
        when(agentService.run(any())).thenThrow(new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试"));
        gateway.handle(user.getId(), "sse-session", "推荐午餐", AgentMode.CONFIRM);
        var auth = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(get("/api/agent/sessions/sse-session/events").with(auth))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("agent-event")));
        mockMvc.perform(get("/api/merchants").with(auth)).andExpect(status().isOk());
    }

    @Test
    void keepsSseSubscriptionOpenAfterReplayingOnlyEventsNewerThanLastEventId() throws Exception {
        UserEntity user = users.save(new UserEntity("agent-live-sse", "agent-live-sse@example.com", "hash"));
        when(agentService.run(any())).thenThrow(new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试"));
        gateway.handle(user.getId(), "live-sse-session", "推荐午餐", AgentMode.CONFIRM);
        var persisted = events.findByUserIdAndSessionIdOrderByIdAsc(user.getId(), "live-sse-session");
        Long lastEventId = persisted.get(0).getId();
        var auth = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(get("/api/agent/sessions/live-sse-session/events")
                        .header("Last-Event-ID", lastEventId).with(auth))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("已接收点餐请求"))));
    }

    @Test
    void publishesEventsCreatedAfterSseConnectionToTheExistingSubscriber() throws Exception {
        UserEntity user = users.save(new UserEntity("agent-realtime", "agent-realtime@example.com", "hash"));
        sessions.start(user.getId(), "realtime-session", AgentMode.CONFIRM, "server-trace");
        var auth = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        var connection = mockMvc.perform(get("/api/agent/sessions/realtime-session/events").with(auth))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        when(agentService.run(any())).thenThrow(new AgentServiceUnavailableException("Agent 服务暂不可用，请稍后重试"));
        gateway.handle(user.getId(), "realtime-session", "推荐午餐", AgentMode.CONFIRM);

        assertThat(connection.getResponse().getContentAsString()).contains("\"type\":\"parsed\"").contains("\"status\":\"running\"");
    }
}
