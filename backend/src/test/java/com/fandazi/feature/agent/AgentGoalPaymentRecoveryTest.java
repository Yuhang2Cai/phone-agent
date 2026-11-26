// GR-05支付恢复测试：已建单只能为原订单签发新确认，旧授权必须立即失效。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实订单和确认授权验证GR-S14/15；重准备服务不注入建单或支付领域方法。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag04-goal-payment-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true",
    "agent.internal-token=synthetic-ag04-payment-recovery-secret-32-characters"})
class AgentGoalPaymentRecoveryTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @Autowired AgentTaskTurnService turns;@Autowired AgentTaskToolBridge tools;@Autowired AgentTaskFenceService fences;
    @Autowired AgentTaskPaymentPreparationService payments;@Autowired JdbcTemplate jdbc;@Autowired StoreRepository stores;@Autowired DealPackageRepository deals;
    AgentTaskToolTestFixtures fixture;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.fandazi.feature.seckill.SeckillTimeSource databaseTime;

    /** 先建立合法活动，再走原工具报价/确认/建单，不能直接伪造秒杀预留或授权。 */
    private void seckillActivity(){
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            var deal=deals.findByPublicId(fixture.dealId).orElseThrow();deal.replaceCompareFacts(2,null,"堂食使用");
            var now=databaseTime.now();em.persist(new com.fandazi.feature.seckill.SeckillActivityEntity("sk-recovery-"+UUID.randomUUID(),deal,now.minusSeconds(60),now.plusSeconds(3600)));em.flush();
        });
    }

    /** 原订单重准备只能沿用原支付截止，不能重新取得五分钟业务期限。 */
    @Test void seckillReprepareRetainsOriginalPaymentDeadline(){
        seckillActivity();var order=paymentStop();String id=(String)order.get("orderId");
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",fixture.turn.turnId());
        var result=payments.reprepare(request(id,1));
        var grant=(Map<?,?>)result.get("grant");
        assertEquals(java.time.Instant.parse((String)order.get("paymentExpiresAt")),java.time.Instant.parse((String)grant.get("expiresAt")));
    }

    /** 支付截止已过但券仍在有效期内时拒绝新授权，不复活原订单，也不撤销原授权。 */
    @Test void seckillExpiredPaymentCannotBeRepreparedUsingVoucherExpiry(){
        seckillActivity();var order=paymentStop();String id=(String)order.get("orderId");
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",fixture.turn.turnId());
        jdbc.update("UPDATE group_buy_orders SET payment_expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE public_id=?",id);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Integer.class,fixture.scope.user);
        assertThrows(ToolBoundaryException.class,()->payments.reprepare(request(id,1)));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Integer.class,fixture.scope.user));
    }

    /** 每例建立独立真实待支付订单，避免共享授权或订单状态。 */
    @BeforeEach void setup(){fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);}

    /** 创建订单后保存支付等待停点，再建立恢复输入；返回原订单及原支付授权。 */
    Map<String,Object> paymentStop(){
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var order=tools.execute("create_group_buy_order",fixture.creation(quote));
        fixture.awaitConfirmation(Map.of("action","pay_mock_order","resourceId",order.get("orderId"),"title","合成支付确认",
            "amountCents",order.get("totalCents"),"ruleVersion","pay-v1"));fixture.admit("合成恢复支付停点");return order;
    }

    /** 生成绑定当前原停点、订单和动作版本的2.3内部请求。 */
    Map<String,Object> request(String orderId,int version){return Map.of("schemaVersion","2.3","requestId","goal-payment-"+version,
        "fence",fixture.fence(),"orderId",orderId,"actionVersion",version);}

    @Test void grS14AndS15ReprepareRevokesOldGrantAndKeepsSingleOriginalOrder(){
        // 2.3轮次只能返回2.3结果；新准备前撤销原授权，同请求重放不再签发第三份授权。
        var order=paymentStop();String orderId=(String)order.get("orderId"),oldGrant=(String)order.get("paymentConfirmationGrantId");
        var oldDisplayed=sessions.owned(fixture.scope.user,fixture.scope.session).getPendingConfirmation();
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",fixture.turn.turnId());
        int grantsBefore=jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Integer.class,fixture.scope.user);
        var first=payments.reprepare(request(orderId,1));var replay=payments.reprepare(request(orderId,1));
        @SuppressWarnings("unchecked") var firstGrant=(Map<String,Object>)first.get("grant");String newGrant=(String)firstGrant.get("confirmationGrantId");

        // JSON列回读可能把Integer恢复为Long；用生产规范摘要比较同体回执，避免把Java数值装箱当协议差异。
        assertEquals("2.3",first.get("schemaVersion"));assertEquals(AgentTaskFingerprint.of(first),AgentTaskFingerprint.of(replay));assertNotEquals(oldGrant,newGrant);
        assertTrue(jdbc.queryForObject("SELECT revoked FROM confirmation_grants WHERE grant_id=?",Boolean.class,oldGrant));
        assertFalse(jdbc.queryForObject("SELECT revoked FROM confirmation_grants WHERE grant_id=?",Boolean.class,newGrant));
        assertEquals(grantsBefore+1,jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Integer.class,fixture.scope.user));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
        assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,orderId));
        // 用户随后点击旧卡片时，服务端按已清除的旧展示摘要拒绝；拒绝不能支付、换订单或新增支付执行记录。
        assertEquals("CONFLICT",assertThrows(ToolBoundaryException.class,
            ()->fences.confirmCurrent(fixture.fence(),fixture.trace(),oldDisplayed)).getCode());
        assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM group_buy_orders WHERE public_id=?",String.class,orderId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions WHERE session_id=? AND tool_name='pay_mock_order'",Integer.class,fixture.scope.session));
    }

    @Test void grS15CannotSwitchOrderOrReviveExpiredOrderAndDoesNotRevokeOnRejectedRequest(){
        // 错对象和已过期订单在签新授权、撤销旧授权之前拒绝，业务表和授权状态均无增量。
        var order=paymentStop();String orderId=(String)order.get("orderId"),oldGrant=(String)order.get("paymentConfirmationGrantId");
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",fixture.turn.turnId());
        int grantsBefore=jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Integer.class,fixture.scope.user);
        assertThrows(ToolBoundaryException.class,()->payments.reprepare(request("different-order",1)));
        assertFalse(jdbc.queryForObject("SELECT revoked FROM confirmation_grants WHERE grant_id=?",Boolean.class,oldGrant));
        jdbc.update("UPDATE group_buy_orders SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE public_id=?",orderId);
        assertThrows(ToolBoundaryException.class,()->payments.reprepare(request(orderId,2)));
        assertEquals(grantsBefore,jdbc.queryForObject("SELECT COUNT(*) FROM confirmation_grants WHERE user_id=?",Integer.class,fixture.scope.user));
        assertFalse(jdbc.queryForObject("SELECT revoked FROM confirmation_grants WHERE grant_id=?",Boolean.class,oldGrant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
    }

    /** 复合图已经展示原订单支付确认后可重新准备；不能仅凭“同账号有该订单”绕过实际停点。 */
    @Test void composite_payment_stop_is_bound_by_validated_confirmation() {
        fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals,"composite");
        var order=paymentStop();String orderId=(String)order.get("orderId");
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",fixture.turn.turnId());
        assertEquals(orderId,jdbc.queryForObject("SELECT pending_payment_order_id FROM agent_conversation_tasks WHERE conversation_task_id=?",String.class,fixture.taskId));
        assertEquals("2.3",payments.reprepare(request(orderId,1)).get("schemaVersion"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
        assertThrows(ToolBoundaryException.class,()->payments.reprepare(request("other-order",2)));
        // 后续转为购买确认时必须清除旧支付停点，不能仅因旧订单尚未支付就重新签发授权。
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.2' WHERE turn_id=?",fixture.turn.turnId());
        var nextQuote=fixture.quote(2);fixture.awaitAndConfirm(nextQuote);
        assertNull(jdbc.queryForObject("SELECT pending_payment_order_id FROM agent_conversation_tasks WHERE conversation_task_id=?",String.class,fixture.taskId));
        jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",fixture.turn.turnId());
        assertThrows(ToolBoundaryException.class,()->payments.reprepare(request(orderId,3)));
    }
}
