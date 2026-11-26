// Task1 修复独立复验：验证真实持久化的空值、嵌套凭证隔离、实时 SSE 与旧事件重放。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 生产事件服务、JPA 转换器和公开 HTTP/SSE 出口全部真实执行；测试事务隔离并自动回滚。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Task1NestedPublicEventAcceptanceTest {
    /** 每次运行使用独立内存库，避免其他 create-drop 上下文关闭时删除本类仍在使用的表。 */
    private static final String TEST_DATABASE_URL = "jdbc:h2:mem:task1-nested-" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /** 只覆盖数据库地址；保留真实迁移、生产事件逻辑、事务回滚和全部既有验收断言。 */
    @DynamicPropertySource
    static void isolateTestDatabase(DynamicPropertyRegistry properties) {
        // 地址在本类内稳定，使同一 Spring 上下文可复用，但不会与其他测试类共用 schema。
        properties.add("spring.datasource.url", () -> TEST_DATABASE_URL);
    }

    @Autowired private AgentEventService events;
    @Autowired private AgentMessageService messages;
    @Autowired private AgentEventRepository repository;
    @Autowired private AgentSessionService sessions;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;

    @Test
    void canonicalIdentitySurvivesPersistenceAndPublicReplayWithoutReplacingCursor() throws Exception {
        // 真实审计入库与公开 DTO 都保留原身份；数据库自增游标必须不同于原 sequence。
        String originalId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        AgentRunRequest run = new AgentRunRequest("42", "task5-identity-session", "查询帮助", "confirm", "task5-trace", runId, Map.of());
        messages.record(run, Map.of("schemaVersion", "1.1", "eventId", originalId, "sequence", 97,
                "runId", runId, "traceId", "task5-trace", "eventType", "progress", "status", "running",
                "payload", Map.of("stepId", "lookup", "label", "已完成查询", "stepStatus", "succeeded")));
        entityManager.flush(); entityManager.clear();
        var loaded = events.after(42L, "task5-identity-session", null).get(0);
        var dto = AgentGatewayController.EventResponse.from(loaded);
        assertThat(dto.status()).isEqualTo("running");
        assertThat(dto.payload()).containsEntry("eventId", originalId).containsEntry("sequence", 97)
                .containsEntry("runId", runId).containsEntry("traceId", "task5-trace").containsEntry("stepStatus", "succeeded");
        assertThat(dto.id()).isEqualTo(loaded.getId()).isNotEqualTo(97L);
    }

    @Test
    void legalNullErrorSurvivesRealDatabaseRoundTrip() throws Exception {
        // 使用真实消息审计入口，强制刷新并清空持久化上下文，排除只读内存对象造成的假通过。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", "query_faq");
        payload.put("ok", true);
        payload.put("data", Map.of("count", 1));
        payload.put("error", null);
        AgentRunRequest run = new AgentRunRequest("42", "acceptance-null-roundtrip", "查询帮助", "confirm", "null-trace");
        messages.record(run, Map.of("eventType", "tool_result", "status", "running", "payload", payload));
        entityManager.flush();
        entityManager.clear();
        var loaded = events.after(42L, "acceptance-null-roundtrip", null);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getPayload()).containsEntry("error", null).containsEntry("ok", true);
        assertThat(mapper.readTree(mapper.writeValueAsString(AgentGatewayController.EventResponse.from(loaded.get(0))))
                .get("payload").get("error").isNull()).isTrue();
    }

    @Test
    void nestedSecretsAreRemovedBeforePersistenceWithoutChangingBusinessValues() throws Exception {
        // 同时覆盖对象、列表、列表中的列表、大小写别名和合法 null；原文中出现字段名不能被删改。
        Map<String, Object> source = privateFixture();
        String original = mapper.writeValueAsString(source);
        AgentEventEntity saved = events.record(42L, "acceptance-nested", "tool_result", "验收工具摘要",
                "prepare_refund", "running", source);
        Long eventId = saved.getId();
        entityManager.flush();
        entityManager.clear();
        AgentEventEntity loaded = repository.findById(eventId).orElseThrow();
        assertPublic(mapper.writeValueAsString(loaded.getPayload()));
        assertThat(loaded.getPayload()).containsEntry("error", null).containsEntry("amountCents", 9900)
                .containsEntry("canRefund", true).containsEntry("query", "原文提及 confirmationGrantId 与 token");
        // 公开副本不能改写内部工具数据，否则后续真正工作流将无法消费内部凭证。
        assertThat(mapper.writeValueAsString(source)).isEqualTo(original);
    }

    @Test
    void historicalUnfilteredRowIsSanitizedByActualSseReplay() throws Exception {
        // 绕过新入库脱敏，真实写入一条部署前的旧记录；公开 HTTP 重放必须再次过滤。
        UserEntity user = users.save(new UserEntity("acceptance-history", "acceptance-history@example.invalid", "unused-test-hash"));
        sessions.start(user.getId(), "acceptance-history-session", AgentMode.CONFIRM, "history-trace");
        AgentEventEntity old = repository.save(new AgentEventEntity(user.getId(), "acceptance-history-session",
                "tool", "旧事件验收", "prepare_refund", "ok", privateFixture()));
        Long oldId = old.getId();
        entityManager.flush();
        entityManager.clear();
        // 明确证明该记录未经过新边界，不接受只测试新建已脱敏行作为历史保护的证据。
        assertThat(repository.findById(oldId).orElseThrow().getPayload()).containsKey("confirmationGrantId");
        var result = mockMvc.perform(get("/api/agent/sessions/acceptance-history-session/events")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                user.getId(), null, List.of()))))
                .andExpect(status().isOk()).andReturn();
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("agent-event");
        assertPublic(stream);
        // 重放只保护公开出口，不偷偷重写历史数据库行。
        assertThat(repository.findById(oldId).orElseThrow().getPayload()).containsKey("confirmationGrantId");
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void liveSsePublishesOnlySanitizedNestedPayload() throws Exception {
        // 实时事件只允许在数据库提交后发布；本用例必须退出测试回滚事务，才能验证真实提交边界的SSE。
        // 先建立真实 MVC SSE 订阅，再由事件服务发布新事件，覆盖实时路径而非只序列化 DTO。
        UserEntity user = users.save(new UserEntity("acceptance-live", "acceptance-live@example.invalid", "unused-test-hash"));
        sessions.start(user.getId(), "acceptance-live-session", AgentMode.CONFIRM, "live-trace");
        var result = mockMvc.perform(get("/api/agent/sessions/acceptance-live-session/events")
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                user.getId(), null, List.of()))))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        events.record(user.getId(), "acceptance-live-session", "tool", "实时事件验收", "prepare_refund", "ok", privateFixture());
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("agent-event");
        assertPublic(stream);
    }

    /** 人工嵌套负例，敏感值只是测试引用；业务文字、金额、布尔、数组顺序和空值必须保留。 */
    private Map<String, Object> privateFixture() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("confirmationGrantId", "acceptance-private-grant");
        root.put("error", null);
        root.put("amountCents", 9900);
        root.put("canRefund", true);
        root.put("query", "原文提及 confirmationGrantId 与 token");
        // 将嵌套样例分段构造，明确分别覆盖鉴权字段和列表深层凭证，不影响外层业务数据。
        Map<String, Object> authorization = Map.of("Authorization", "acceptance-private-auth", "ApiKey", "acceptance-private-api",
                "JWT", "acceptance-private-jwt", "PASSWORD", "acceptance-private-password",
                "secret", "acceptance-private-secret", "redemptionToken", "acceptance-private-redemption", "orderId", "acceptance-order");
        List<Object> items = Arrays.asList(null,
                Map.of("token", "acceptance-private-token", "accessToken", "acceptance-private-access",
                        "refreshToken", "acceptance-private-refresh", "enabled", false), List.of(authorization));
        root.put("data", Map.of("confirmationGrant", "acceptance-private-legacy", "grantId", "acceptance-private-alias",
                "publicId", "acceptance-voucher", "tokenCount", 7, "items", items));
        return root;
    }

    /** 检查敏感字段键与值均不公开，同时证明正常业务内容没有被字符串替换或类型转换破坏。 */
    private void assertPublic(String serialized) {
        assertThat(serialized).doesNotContain("acceptance-private-", "\"confirmationGrantId\":", "\"confirmationGrant\":",
                "\"grantId\":", "\"token\":", "\"accessToken\":", "\"refreshToken\":", "\"Authorization\":",
                "\"ApiKey\":", "\"JWT\":", "\"PASSWORD\":", "\"secret\":", "\"redemptionToken\":");
        assertThat(serialized).contains("\"amountCents\":9900", "\"canRefund\":true", "\"error\":null",
                "\"tokenCount\":7", "\"enabled\":false", "acceptance-voucher", "acceptance-order",
                "原文提及 confirmationGrantId 与 token");
    }
}
