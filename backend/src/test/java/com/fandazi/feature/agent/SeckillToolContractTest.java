// 秒杀工具契约回归：使用生产校验器核对新增事实，防止成功交易被旧协议判为内部错误。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 仅验证跨入口的结构边界；真实服务、确认与数据库结果另由组合测试覆盖。 */
class SeckillToolContractTest {
    private final AgentToolSchemaValidator validator=new AgentToolSchemaValidator(new ObjectMapper());
    /** 固定合法时间属于合成协议样本，不以当前时间伪造实际订单。 */
    private static final String TIME="2026-10-29T00:00:00Z";

    /** 秒杀报价携带活动版本、数据库时间和剩余额度，仍不得接受客户端自行增加的价格字段。 */
    @Test void quoteAcceptsActivityFactsButRejectsUnknownFields(){
        var quote=new LinkedHashMap<String,Object>(Map.of("quoteId","quote-test","dealId","deal-test","quantity",1,
                "subtotalCents",7900,"discountCents",3910,"totalCents",3990,"expiresAt",TIME,
                "confirmationGrantId","grant-test","dealVersion",1));
        quote.putAll(Map.of("activityId","activity-test","activityVersion",1,"serverTime",TIME,"remainingQuota",2));
        assertThatCode(()->validator.validateData("create_purchase_quote",quote)).doesNotThrowAnyException();
        quote.put("clientPrice",1);
        assertThatThrownBy(()->validator.validateData("create_purchase_quote",quote)).isInstanceOf(ToolBoundaryException.class);
    }

    /** 待支付、超时和未到使用日期的券均不能被投影成可用券，订单保留独立支付截止。 */
    @Test void orderAndVoucherAcceptRestrictedRights(){
        for(String status:List.of("PENDING_PAYMENT","CLOSED_TIMEOUT","NOT_YET_VALID","UNAVAILABLE")){
            var voucher=new LinkedHashMap<String,Object>(Map.of("publicId","voucher-test","orderId","order-test",
                    "dealId","deal-test","dealTitle","合成秒杀券","storeId","store-test","storeName","合成门店",
                    "status",status,"canRefund",false,"expiresAt",TIME));
            voucher.put("redeemedAt",null);voucher.put("validFrom","2026-10-30");voucher.put("canUse",false);
            var order=new LinkedHashMap<String,Object>(Map.of("orderId","order-test","dealId","deal-test","storeId","store-test",
                    "quantity",1,"totalCents",3990,"status","CLOSED_TIMEOUT","voucherCount",1,"createdAt",TIME,
                    "canReview",false,"vouchers",List.of(voucher)));
            order.putAll(Map.of("activityId","activity-test","paymentExpiresAt",TIME,"serverTime",TIME));
            order.put("closedAt",null);order.put("closeReason",null);
            assertThatCode(()->validator.validateData("get_order_detail",Map.of("order",order))).doesNotThrowAnyException();
        }
    }

    /** 保留原错误大类，稳定原因仅作为附加信息；三代信封都不能把业务拒绝误标可自动重试。 */
    @Test void allEnvelopesAcceptStableBusinessReason(){
        for(String version:List.of("1.1","2.0","2.1")){
            var result=Map.<String,Object>of("schemaVersion",version,"ok",false,"data",Map.of(),"error",
                    Map.of("code","CONFLICT","reasonCode","NOT_STARTED","message","活动尚未开始","retryable",false));
            assertThatCode(()->{switch(version){case "1.1"->validator.validateResult(result);
                case "2.0"->validator.validateV2Result(result);default->validator.validateV21Result(result);}}).doesNotThrowAnyException();
        }
    }

    /** 控制器实际出口必须映射业务大类，而不是把新增原因直接塞进旧 code 枚举。 */
    @Test void controllersPreserveReasonWithoutMakingBusinessRejectionRetryable(){
        var jwt=org.mockito.Mockito.mock(InternalV1JwtValidator.class);
        var bridge=org.mockito.Mockito.mock(AgentV1ToolBridge.class);
        var newer=org.mockito.Mockito.mock(AgentTaskToolBridge.class);
        var audit=org.mockito.Mockito.mock(AgentV1ToolAudit.class);
        var mapper=new ObjectMapper();
        var input=new LinkedHashMap<String,Object>(Map.of("schemaVersion","1.1","userId","1","sessionId","session-test",
                "traceId","trace-test","runId","run-test","taskId","task-test","idempotencyKey","run-test:task-test:1",
                "payload",Map.of("dealId","deal-test","quantity",1)));
        input.put("actionVersion",1);
        for(String reason:List.of("NOT_STARTED","SOLD_OUT","PAYMENT_EXPIRED","ORDER_CLOSED")){
            var denied=new com.fandazi.feature.seckill.SeckillRejectedException(reason,"合成业务拒绝");
            org.mockito.Mockito.doThrow(denied).when(bridge).execute(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
            // 使用真实输出校验器检查映射；输入隔离掉会话和任务结构，它们由既有集成测试验证。
            var schema=org.mockito.Mockito.mock(AgentToolSchemaValidator.class);
            var v1=new InternalV1ToolController(jwt,schema,bridge,audit,mapper);
            var result=v1.call("create_purchase_quote","test",input).getBody();
            assertThat(((Map<?,?>)result.get("error")).get("reasonCode")).isEqualTo(reason);
            assertThat(((Map<?,?>)result.get("error")).get("retryable")).isEqualTo(false);
            validator.validateResult(result);
            org.mockito.Mockito.doThrow(denied).when(newer).execute(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
            var v2=new InternalV2ToolController(jwt,schema,newer,audit,mapper,true,true);
            input.put("schemaVersion","2.0");
            result=v2.call("create_purchase_quote","test",input).getBody();
            assertThat(((Map<?,?>)result.get("error")).get("reasonCode")).isEqualTo(reason);
            assertThat(((Map<?,?>)result.get("error")).get("retryable")).isEqualTo(false);
            validator.validateV2Result(result);
            input.put("schemaVersion","1.1");
        }
    }
}
