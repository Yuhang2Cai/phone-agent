// 任务目录HTTP组件测试：真实安全链与MVC，只读GET不触达模型/工具且不写业务表。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** MockMvc验证路由/身份，不代替TL-02真实MySQL和Spring重启HTTP证据。 */
@SpringBootTest(properties={
    "spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-directory;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "security.jwt.secret=synthetic-test-only-secret-0123456789012345678901234567890123456789"})
@AutoConfigureMockMvc
class AgentTaskDirectoryHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @MockBean AgentServiceClient model;
    private AgentTaskTestFixtures fixture;
    /** 重跑不依赖其他例的数据库状态，也不清空已有合成记录。 */
    @BeforeEach void setUp() { fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc); }
    /** 安全链主体只包含已核验Long账号ID，不从请求参数设置用户。 */
    private UsernamePasswordAuthenticationToken identity(Long user) { return new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_USER"))); }
    /** 统计执行记录、准备/授权和四类业务表，GET前后应完全相同。 */
    private List<Long> writes() {
        return List.of("agent_tool_executions","purchase_quotes","confirmation_grants","group_buy_orders","refund_requests","complaint_tickets")
                .stream().map(t -> jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class)).toList();
    }
    @Test void readDirectoryUsesOwnerAndHasNoModelToolOrBusinessSideEffect() throws Exception {
        // 不以200单独判通过：核对精确投影、数据库增量与模型边界零调用。
        fixture.selection();var expected=fixture.directory();var before=writes();
        String body=mvc.perform(get("/api/agent/sessions/{id}/tasks",fixture.session).with(authentication(identity(fixture.user))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // HTTP JSON不保留Java的Integer/Long区别，双方按同一JSON解析后比较实际结构和值。
        assertEquals(json.readTree(json.writeValueAsString(expected)),json.readTree(body));assertEquals(before,writes());verifyNoInteractions(model);
        assertFalse(body.contains("confirmationGrant"));assertFalse(body.contains("synthetic-hash"));
    }
    @Test void anonymousIs401AndCrossOwnerLooksLikeMissingSession() throws Exception {
        // 不存在与越权都404，不能通过目录接口探测其他账号会话。
        mvc.perform(get("/api/agent/sessions/{id}/tasks",fixture.session)).andExpect(status().isUnauthorized());
        var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        mvc.perform(get("/api/agent/sessions/{id}/tasks",fixture.session).with(authentication(identity(other.user)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/sessions/{id}/tasks","missing-synthetic").with(authentication(identity(other.user)))).andExpect(status().isNotFound());verifyNoInteractions(model);
    }
    @Test void emptyDirectoryReadDoesNotInitializeSourceOrAdvanceVersions() throws Exception {
        // 空目录合法；重复读取不生成来源、run、焦点或历史写入。
        var before=fixture.directory();long sources=jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",Long.class,fixture.session);
        for(int i=0;i<3;i++)mvc.perform(get("/api/agent/sessions/{id}/tasks",fixture.session).with(authentication(identity(fixture.user)))).andExpect(status().isOk());
        assertEquals(before,fixture.directory());assertEquals(0L,sources);
        assertEquals(sources,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",Long.class,fixture.session));verifyNoInteractions(model);
    }
}
