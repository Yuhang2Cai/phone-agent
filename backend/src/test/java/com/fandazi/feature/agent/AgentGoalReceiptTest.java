// GR-05目标回执测试：四种真实写入只能通过原执行记录恢复最小安全资源。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实报价、确认、订单、支付、退款和投诉领域服务建立执行记录；测试不得直接向
 * agent_tool_executions 或业务表插入伪成功行。协议版本只标记原受理轮次的回执格式。
 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:ag04-goal-receipts;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true",
    "agent.internal-token=synthetic-ag04-goal-receipt-secret-32-characters"})
class AgentGoalReceiptTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @Autowired AgentTaskTurnService turns;@Autowired AgentTaskToolBridge tools;@Autowired AgentTaskFenceService fences;
    @Autowired AgentTaskReceiptService receipts;@Autowired JdbcTemplate jdbc;@Autowired StoreRepository stores;@Autowired DealPackageRepository deals;
    AgentTaskToolTestFixtures fixture;

    /** 每例建立独立账号、会话、任务和真实套餐，避免跨例复用执行键或业务资源。 */
    @BeforeEach void setup(){fixture=new AgentTaskToolTestFixtures(users,sessions,registry,turns,tools,fences,jdbc,stores,deals);}

    /**
     * 将已经实际执行的原轮次标记为2.3回执格式。业务结果仍全部由真实工具产生；这里不改
     * task、执行键、结果JSON或领域表，只模拟2.3 Agent在网络响应丢失后回查原turn。
     */
    void markGoalProtocol(InternalV2ToolRequest request){jdbc.update("UPDATE agent_task_turns SET protocol_version='2.3' WHERE turn_id=?",request.taskFence().get("turnId"));}

    /** 按原请求完整scope读取回执，不能使用当前焦点或最新turn替换原执行归属。 */
    Map<String,Object> read(InternalV2ToolRequest request){return receipts.read(fixture.scope.user,fixture.scope.session,fixture.scope.epoch,fixture.taskId,
        request.runId(),(String)request.taskFence().get("turnId"),request.idempotencyKey());}

    /** 保存一次等待确认停点，再以新输入明确确认同一摘要；不直接修改confirmed字段。 */
    void confirm(Map<String,Object> summary){
        fixture.awaitConfirmation(summary);var displayed=sessions.owned(fixture.scope.user,fixture.scope.session).getPendingConfirmation();
        fixture.admit("合成用户明确确认");fences.confirmCurrent(fixture.fence(),fixture.trace(),displayed);
    }

    /** 创建并真实支付一份订单，返回创建请求、支付请求、订单结果，供后续回执和售后使用。 */
    Purchase purchase(){
        var quote=fixture.quote(1);fixture.awaitAndConfirm(quote);var createRequest=fixture.creation(quote);var order=tools.execute("create_group_buy_order",createRequest);
        confirm(Map.of("action","pay_mock_order","resourceId",order.get("orderId"),"title","确认原订单支付",
            "amountCents",order.get("totalCents"),"ruleVersion","pay-v1"));
        var payRequest=fixture.request("pay-order",1,Map.of("orderId",order.get("orderId"),"confirmationGrantId",order.get("paymentConfirmationGrantId")));
        var paid=tools.execute("pay_mock_order",payRequest);return new Purchase(createRequest,payRequest,order,paid);
    }

    @Test void grS09OrderAndPaymentReceiptsKeepOriginalOrderWithoutPrivateGrant(){
        // 建单和支付回执都锚定同一个真实orderId；不得返回支付授权或创建第二单。
        var value=purchase();markGoalProtocol(value.createRequest);markGoalProtocol(value.payRequest);
        var created=read(value.createRequest);var paid=read(value.payRequest);
        assertEquals("2.3",created.get("schemaVersion"));assertEquals("create_group_buy_order",created.get("toolName"));
        assertEquals(value.order.get("orderId"),((Map<?,?>)created.get("resources")).get("orderId"));
        assertEquals("pay_mock_order",paid.get("toolName"));assertEquals(value.order.get("orderId"),((Map<?,?>)paid.get("resources")).get("orderId"));
        assertEquals("PAID",((Map<?,?>)paid.get("summary")).get("status"));
        assertFalse(created.toString().contains("confirmationGrant"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM group_buy_orders WHERE user_id=?",Integer.class,fixture.scope.user));
    }

    @Test void grS09RefundAndComplaintMapActualRequestAndTicketFields(){
        // 退款和投诉必须先真实准备、展示、确认再提交；回执映射实际requestId/ticketId，不能依赖不存在的旧字段。
        var value=purchase();String voucher=(String)((Map<?,?>)((List<?>)value.order.get("vouchers")).get(0)).get("publicId");
        var refundPreparation=tools.execute("prepare_refund",fixture.request("refund-prepare",1,Map.of("voucherId",voucher)));
        confirm(Map.of("action","request_refund","resourceId",voucher,"title","确认退款申请","amountCents",refundPreparation.get("amountCents"),
            "dealTitle",refundPreparation.get("dealTitle"),"reason","行程取消","ruleVersion","refund-v1"));
        var refundRequest=fixture.request("refund-write",1,Map.of("voucherId",voucher,"reason","行程取消","confirmationGrantId",refundPreparation.get("confirmationGrantId")));
        var refund=tools.execute("request_refund",refundRequest);

        var complaintPreparation=tools.execute("prepare_complaint",fixture.request("complaint-prepare",1,Map.of("orderId",value.order.get("orderId"))));
        confirm(Map.of("action","submit_complaint","resourceId",value.order.get("orderId"),"title","确认投诉","amountCents",0,
            "ruleVersion","complaint-v1","subject","服务问题","description","希望核实服务流程"));
        var complaintRequest=fixture.request("complaint-write",1,Map.of("orderId",value.order.get("orderId"),"subject","服务问题",
            "description","希望核实服务流程","confirmationGrantId",complaintPreparation.get("confirmationGrantId")));
        var complaint=tools.execute("submit_complaint",complaintRequest);

        markGoalProtocol(refundRequest);markGoalProtocol(complaintRequest);var refundReceipt=read(refundRequest);var complaintReceipt=read(complaintRequest);
        assertEquals(refund.get("requestId"),((Map<?,?>)refundReceipt.get("resources")).get("refundRequestId"));
        assertFalse(((Map<?,?>)refundReceipt.get("resources")).containsKey("refundId"));
        assertEquals(complaint.get("ticketId"),((Map<?,?>)complaintReceipt.get("resources")).get("complaintTicketId"));
        assertFalse(((Map<?,?>)complaintReceipt.get("resources")).containsKey("complaintId"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM refund_requests WHERE user_id=?",Integer.class,fixture.scope.user));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM complaint_tickets WHERE user_id=?",Integer.class,fixture.scope.user));
    }

    /** 组合购买夹具保留每个原请求，确保回查使用写入发生时的run/turn/key。 */
    record Purchase(InternalV2ToolRequest createRequest,InternalV2ToolRequest payRequest,Map<String,Object> order,Map<String,Object> paid){}
}
