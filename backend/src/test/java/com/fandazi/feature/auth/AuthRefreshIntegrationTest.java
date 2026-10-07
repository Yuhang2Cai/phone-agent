/* 长期登录接口回归：用实际安全过滤器、事务与数据库验证轮换、恢复、撤销，防止只在客户端假登录。 */
package com.fandazi.feature.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 每项使用独立合成账号，避免改变历史回归夹具；所有凭据仅存在测试内存。 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRefreshIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    /** 首次注册即获得完整续期包；服务端不得把60分钟JWT当长期登录完成。 */
    @Test void loginIssuesRefreshPackage() throws Exception {
        JsonNode login=login();
        assertThat(login.path("refreshToken").asText()).isNotBlank();
        assertThat(login.path("sessionId").asText()).isNotBlank();
        assertThat(login.path("accessExpiresAtEpochMs").asLong()).isGreaterThan(System.currentTimeMillis());
        assertThat(login.path("refreshExpiresAtEpochMs").asLong()).isGreaterThan(login.path("accessExpiresAtEpochMs").asLong());
    }

    /** 同请求重试恢复同一轮换结果，数据库只有摘要，避免网络丢响应强迫用户重登。 */
    @Test void rotatesAndRecoversSameRequestWithoutPlaintextStorage() throws Exception {
        JsonNode first=login();String key=UUID.randomUUID().toString();
        JsonNode next=refresh(first,key,200),retry=refresh(first,key,200);
        assertThat(next.path("refreshToken").asText().equals(first.path("refreshToken").asText())).isFalse();
        assertThat(retry.path("refreshToken").asText().equals(next.path("refreshToken").asText())).isTrue();
        assertThat(retry.path("refreshExpiresAtEpochMs").asLong()).isEqualTo(next.path("refreshExpiresAtEpochMs").asLong());
        String hash=jdbc.queryForObject("select token_hash from auth_sessions where id=?",String.class,first.path("sessionId").asText());
        assertThat(hash.length()).isEqualTo(64);
        assertThat(hash.equals(next.path("refreshToken").asText())).isFalse();
        me(next,200);
    }

    /** 上一轮凭据被另一个请求重用时撤销会话，旧JWT也必须立即拒绝。 */
    @Test void replayRevokesAccessAndRefresh() throws Exception {
        JsonNode first=login(),next=refresh(first,UUID.randomUUID().toString(),200);
        refresh(first,UUID.randomUUID().toString(),401);
        me(first,401);me(next,401);refresh(next,UUID.randomUUID().toString(),401);
    }

    /** 猜到会话ID不能使别人退出；无正确秘密的请求只拒绝自己。 */
    @Test void invalidSecretCannotRevokeKnownSession() throws Exception {
        JsonNode first=login();
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of(
            "refreshToken",first.path("sessionId").asText()+"."+"x".repeat(43),"requestId",UUID.randomUUID().toString())))).andExpect(status().isUnauthorized());
        me(first,200);refresh(first,UUID.randomUUID().toString(),200);
    }

    /** 在线退出幂等，同时拒绝刷新和还没自然到期的访问JWT。 */
    @Test void logoutIsIdempotentAndImmediatelyRevokes() throws Exception {
        JsonNode first=login();String body=mapper.writeValueAsString(java.util.Map.of("refreshToken",first.path("refreshToken").asText()));
        for(int i=0;i<2;i++)mvc.perform(post("/api/auth/logout").contentType("application/json").content(body)).andExpect(status().isNoContent());
        me(first,401);refresh(first,UUID.randomUUID().toString(),401);
    }

    /** 空闲到期与绝对到期分别验证，禁止仅客户端日期判断或继续使用有效JWT绕过。 */
    @Test void expiredIdleAndAbsoluteSessionsAreRejected() throws Exception {
        for(String column:new String[]{"idle_expires_at","absolute_expires_at"}) {
            JsonNode first=login();jdbc.update("update auth_sessions set "+column+"=? where id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),first.path("sessionId").asText());
            refresh(first,UUID.randomUUID().toString(),401);me(first,401);
        }
    }

    /** 同一请求并发重试只有一次逻辑轮换，证明实际事务锁而非测试串行假象。 */
    @Test void concurrentSameRequestHasOneSuccessor() throws Exception {
        JsonNode first=login();String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->refresh(first,key,200));var b=pool.submit(()->refresh(first,key,200));
            assertThat(a.get().path("refreshToken").asText().equals(b.get().path("refreshToken").asText())).isTrue();
        } finally {pool.shutdownNow();}
    }

    /** 使用真实注册接口建立独立身份，避免复用任何私人账号密码。 */
    private JsonNode login() throws Exception {
        String body=mapper.writeValueAsString(java.util.Map.of("username","ll-"+UUID.randomUUID(),"password","synthetic-password-123"));
        String response=mvc.perform(post("/api/auth/register").contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode value=mapper.readTree(response);
        assertThat(value.path("refreshToken").asText()).as("长期登录必须下发刷新凭据").isNotBlank();
        return value;
    }
    /** 只核对状态与内存JSON，不把秘密写入公共证据。 */
    private JsonNode refresh(JsonNode session,String key,int code) throws Exception {
        String body=mapper.writeValueAsString(java.util.Map.of("refreshToken",session.path("refreshToken").asText(),"requestId",key));
        String response=mvc.perform(post("/api/auth/refresh").contentType("application/json").content(body)).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
    /** 通过本人受保护接口核对JWT的真实权限状态。 */
    private void me(JsonNode session,int code) throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization","Bearer "+session.path("token").asText())).andExpect(status().is(code));
    }
}
