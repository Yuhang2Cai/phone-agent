package com.fandazi.feature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fandazi.feature.agent.AgentEventService;
import com.fandazi.feature.agent.AgentGatewayController;
import com.fandazi.feature.agent.AgentGatewayService;
import com.fandazi.feature.agent.AgentSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 综合验收发现首次SSE订阅可能早于会话创建；验证真实控制器的领域错误保持既有JSON错误协议。
 * 只替代会话归属依赖，不启动Agent/数据库/录音；媒体类型协商由真实Spring MVC处理。
 */
class DomainExceptionMediaTypeTest {
    /**
     * 输入为仅接受SSE的首次订阅，归属校验返回“会话不存在”；预期400与明确JSON错误。
     * 防止异常处理器再次按SSE协商Map失败，导致领域错误变成不可表示响应或服务器500。
     */
    @Test
    void missingSessionWithSseAcceptReturnsExistingJsonDomainError() throws Exception {
        // 固定归属校验触发点，流服务不得在未通过归属校验时订阅；不伪造HTTP错误响应。
        AgentSessionService sessions = mock(AgentSessionService.class);
        String session = "00000000-0000-4000-8000-000000000018";
        doThrow(new DomainException("会话不存在")).when(sessions).owned(null, session);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentGatewayController(
                mock(AgentGatewayService.class), sessions, mock(AgentEventService.class)))
                .setControllerAdvice(new DomainExceptionHandler()).build();

        // 真实客户端发送text/event-stream，错误出口仍须指定JSON；既有400领域协议保持不变。
        // MockMvc不经过容器500兜底，因此把不可表示的异常明确纳入“不可抛出”断言，而非测试环境错误。
        assertDoesNotThrow(() -> mvc.perform(get("/api/agent/sessions/{sessionId}/events", session)
                        .accept(MediaType.TEXT_EVENT_STREAM)), "领域错误必须生成既有JSON响应，不得逃逸为Servlet异常")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DOMAIN_ERROR"))
                .andExpect(jsonPath("$.error").value("会话不存在"));
    }
}
