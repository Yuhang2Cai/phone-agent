// 执行记录回查测试：使用真实工具已提交记录，HTTP回查只能返回原归属安全摘要，未知不代表未执行。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;

/** 回查不是第18个模型工具，不调用领域写，不返回内部grant、请求正文或SQL。 */
@SpringBootTest(properties={"spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-receipts;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false",
    "agent.internal-token=synthetic-ag03-fence-only-hmac-secret-32-characters"})
@AutoConfigureMockMvc
class AgentTaskReceiptTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;
    @Autowired AgentTaskToolBridge tools;@Autowired AgentTaskFenceService fences;@Autowired JdbcTemplate jdbc;@Autowired StoreRepository stores;@Autowired DealPackageRepository deals;
    @Autowired MockMvc http;@Autowired ObjectMapper json;AgentTaskToolTestFixtures fixture;
    /** 每例通过真实服务准备合成对象，回查所用身份不能由模型构造。 */
    @BeforeEach void setup(){fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);}
    /** 短时JWT只绑定此合成账号，用于验证真实内部安全链。 */
    String token(){var now=Instant.now();return Jwts.builder().subject(String.valueOf(fixture.scope.user)).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(AgentTaskFenceTest.KEY.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();}
    /** 固定原task/run/turn/key，服务不能使用当前焦点猜原记录归属。 */
    org.springframework.mock.web.MockHttpServletResponse read(InternalV2ToolRequest request,String key) throws Exception {
        return http.perform(get("/internal/v2/task-executions/{key}",key).header("Authorization","Bearer "+token())
            .param("userId",String.valueOf(fixture.scope.user)).param("sessionId",fixture.scope.session).param("conversationEpoch",fixture.scope.epoch)
            .param("conversationTaskId",fixture.taskId).param("runId",request.runId()).param("turnId",(String)request.taskFence().get("turnId"))).andReturn().getResponse();
    }
    @Test void receiptMustReturnCommittedFactWithoutPrivateGrant() throws Exception {
        // 指定红测：缺失回查路由不能当未知结果；已提交订单摘要不能泄漏支付授权。
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var request=fixture.creation(quote);var order=tools.execute("create_group_buy_order",request);
        var response=read(request,request.idempotencyKey());assertEquals(200,response.getStatus(),"执行回查必须返回持久事实而非404");
        var result=json.readTree(response.getContentAsString());assertEquals("committed",result.path("state").asText());assertEquals(order.get("orderId"),result.path("resources").path("orderId").asText());
        assertFalse(response.getContentAsString().contains((String)order.get("paymentConfirmationGrantId")));assertFalse(response.getContentAsString().contains("confirmationGrant"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
    }
    @Test void absentRecordIsUnknownAndReadDoesNotExecute() throws Exception {
        // 查不到不能断言未执行；只回unknown，不调用原工具补做。
        var request=fixture.request("not-sent",1,Map.of("question","合成查询"));long before=jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions",Long.class);
        var response=read(request,request.idempotencyKey());assertEquals(200,response.getStatus(),"未知执行也必须通过受控回查入口");assertEquals("unknown",json.readTree(response.getContentAsString()).path("state").asText());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions",Long.class));
    }
    /** 建立真实购买后支付等待停点，恢复只取得新轮次，不能先支付。 */
    Map<String,Object> paymentStop(){
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var order=tools.execute("create_group_buy_order",fixture.creation(quote));
        fixture.awaitConfirmation(Map.of("action","pay_mock_order","resourceId",order.get("orderId"),"title","合成支付确认","amountCents",order.get("totalCents"),"ruleVersion","pay-v1"));
        fixture.admit("合成恢复支付停点");return order;
    }
    /** 内部支付重准备不是17工具之一；只允许原scope/fence/order/actionVersion。 */
    org.springframework.mock.web.MockHttpServletResponse preparePayment(String orderId,int version) throws Exception {
        var raw=Map.of("schemaVersion","2.2","requestId","synthetic-payment-refresh-"+version,"fence",fixture.fence(),"orderId",orderId,"actionVersion",version);
        return http.perform(post("/internal/v2/task-payments/reprepare").header("Authorization","Bearer "+token()).contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
    }
    @Test void paymentReprepareOnlySignsNewGrantForOriginalOrder() throws Exception {
        // S15：恢复支付签新授权但不重新创建订单/支付；重复同请求只回读同一授权。
        var order=paymentStop();var response=preparePayment((String)order.get("orderId"),1);
        assertEquals(200,response.getStatus(),"支付停点重准备必须存在受控内部入口");var value=json.readTree(response.getContentAsString());
        assertEquals(order.get("orderId"),value.path("summary").path("resourceId").asText());assertEquals("pay_mock_order",value.path("grant").path("action").asText());
        assertNotEquals(order.get("paymentConfirmationGrantId"),value.path("grant").path("confirmationGrantId").asText());
        assertEquals(response.getContentAsString(),preparePayment((String)order.get("orderId"),1).getContentAsString());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
        assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,order.get("orderId")));
    }
    @Test void paymentReprepareCannotChangeOrderOrReviveExpiredOrder() throws Exception {
        // 原订单绑定不是“本人任意订单”；业务到期也不能借新grant期限复活。
        var order=paymentStop();assertEquals(409,preparePayment("synthetic-other-order",1).getStatus(),"支付重准备不能换目标订单");
        jdbc.update("UPDATE group_buy_orders SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE public_id=?",order.get("orderId"));
        assertEquals(409,preparePayment((String)order.get("orderId"),2).getStatus());
        assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,order.get("orderId")));
    }
    @Test void receiptRejectsMissingJwtAndOldEpoch() throws Exception {
        // 私有回查也必须鉴权；即使知道原task/key，相同session的旧epoch仍不能取结果。
        var request=fixture.request("query",1,Map.of("question","合成问题"));tools.execute("query_faq",request);
        var base=get("/internal/v2/task-executions/{key}",request.idempotencyKey()).param("userId",String.valueOf(fixture.scope.user)).param("sessionId",fixture.scope.session)
            .param("conversationEpoch",fixture.scope.epoch).param("conversationTaskId",fixture.taskId).param("runId",request.runId()).param("turnId",fixture.turn.turnId());
        assertEquals(403,http.perform(base).andReturn().getResponse().getStatus());
        var old=http.perform(get("/internal/v2/task-executions/{key}",request.idempotencyKey()).header("Authorization","Bearer "+token()).param("userId",String.valueOf(fixture.scope.user))
            .param("sessionId",fixture.scope.session).param("conversationEpoch","synthetic-old-epoch").param("conversationTaskId",fixture.taskId).param("runId",request.runId()).param("turnId",fixture.turn.turnId())).andReturn().getResponse();
        assertEquals(403,old.getStatus());
    }
    @Test void paymentReprepareRejectsPaidOrderWithoutIssuingGrant() throws Exception {
        // 原订单即使仍挂在等待确认停点，支付事实已改变时不能签发第二份可支付授权。
        var order=paymentStop();jdbc.update("UPDATE group_buy_orders SET status='PAID' WHERE public_id=?",order.get("orderId"));
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Long.class,fixture.scope.user);
        assertEquals(409,preparePayment((String)order.get("orderId"),1).getStatus());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Long.class,fixture.scope.user));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,order.get("orderId")));
    }
    @Test void paymentPreparationSharesRateLimitAndReplayDoesNotConsumeQuota() throws Exception {
        // 购买报价已使用一次配额；同原请求回读不增加执行记录，第三十一次新准备须被拒且不能签grant。
        var order=paymentStop();String id=(String)order.get("orderId");
        for(int version=1;version<=29;version++)assertEquals(200,preparePayment(id,version).getStatus());
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Long.class,fixture.scope.user);
        assertEquals(200,preparePayment(id,29).getStatus());
        var rejected=preparePayment(id,30);assertEquals(409,rejected.getStatus());
        assertEquals("RATE_LIMITED",json.readTree(rejected.getContentAsString()).path("error").path("code").asText());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Long.class,fixture.scope.user));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
    }
}
