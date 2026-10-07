package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 验证 Spring 记忆代理使用服务端身份，不让 Android 指定 owner；没有实际网络业务写入。 */
class AgentMemoryClientTest {
    @Test void proxyUsesAuthenticatedSubjectAndPreservesSafeStatus() {
        // 请求身份来自 Long principal，内部JWT一分钟有效，响应只原样传递管理协议。
        var builder = RestClient.builder().baseUrl("http://memory.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new AgentMemoryClient(builder.build(), "test-memory-secret-with-at-least-32-bytes", new ObjectMapper());
        server.expect(requestTo("http://memory.test/internal/v1/agent/memories"))
            .andExpect(method(HttpMethod.GET)).andExpect(request -> assertTrue(request.getHeaders().getFirst("Authorization").startsWith("Bearer ")))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(200, client.request(42L, "GET", "", null).getStatusCode().value());
        server.verify();
    }
}
