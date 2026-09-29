// 智能助手网关：绑定用户会话、授权和后端代理调用。
package com.fandazi.feature.agent;

import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.cart.CartQuote;
import com.fandazi.feature.cart.CartService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service public class AgentGatewayService {
    private final AgentSessionService sessions; private final AgentServiceClient client; private final AgentEventService events; private final CartService carts; private final AddressService addresses;
// 初始化对象依赖与默认状态。
    public AgentGatewayService(AgentSessionService sessions, AgentServiceClient client, AgentEventService events, CartService carts, AddressService addresses) { this.sessions=sessions; this.client=client; this.events=events; this.carts=carts; this.addresses=addresses; }
// 处理当前数据。
    public AgentResult handle(Long userId,String sessionId,String text,AgentMode mode) {
        String traceId=UUID.randomUUID().toString(); AgentSession session=sessions.start(userId,sessionId,mode,traceId);
        if (mode == AgentMode.CONFIRM && isConfirmation(text)) sessions.confirm(userId, sessionId);
        events.record(userId, sessionId, "parsed", "已接收点餐请求", null, "running", java.util.Map.of());
        try {
            // 将用户身份、会话和模式作为 Spring 已校验的上下文传给独立 Agent，模型不能自行伪造这些字段。
            AgentResult result=client.run(new AgentRunRequest(userId.toString(),sessionId,text,mode == AgentMode.THIS_TASK_AUTO ? "auto_order" : "order",traceId));
            result=completeConfirmation(userId, mode, result);
            bindAuthorizedCandidate(userId, sessionId, result);
            // 旧替身返回的事件仍由网关写入；V1 客户端已经逐行记录，避免同一事件重复审计。
            if (!result.eventsRecorded()) for (AgentResult.Event event: result.events()) events.record(userId,sessionId,event.type(),event.summary(),event.toolName(),event.status(),event.payload());
            events.record(userId,sessionId,result.status() == AgentResult.Status.FAILED ? "failed" : "completed",result.message(),null,result.status().name().toLowerCase(),result.candidateCart());
            return result;
        } catch (AgentServiceUnavailableException exception) {
            events.record(userId,sessionId,"failed",exception.getMessage(),null,"retryable",java.util.Map.of()); return AgentResult.failed(exception.getMessage());
        }
    }
// 处理 `completeConfirmation` 相关逻辑。
    private AgentResult completeConfirmation(Long userId, AgentMode mode, AgentResult result) {
        if (result.status()!=AgentResult.Status.AWAITING_CONFIRMATION) return result;
        Long cartId=number(result.candidateCart().get("cartDraftId")); Long addressId=number(result.candidateCart().get("addressId")); Object maximum=result.candidateCart().get("maxAmount");
        if(cartId==null||addressId==null||maximum==null) return result;
        var draft=carts.owned(userId,cartId); CartQuote quote=carts.validate(userId,cartId); var address=addresses.owned(userId,addressId);
        List<Map<String,Object>> items=quote.lines().stream().map(line->Map.<String,Object>of("name",line.dishName(),"specification",line.optionSummary().isBlank()?"无额外规格":line.optionSummary(),"price",line.unitPrice(),"quantity",line.quantity())).toList();
        Map<String,Object> cart=new LinkedHashMap<>(); cart.put("merchantName",draft.getMerchant().getName()); cart.put("items",items); cart.put("cartDraftId",cartId); cart.put("workflowId",result.candidateCart().get("workflowId")); cart.put("address",address.getRecipient()+" "+address.getPhone()+" "+address.getDetail()); cart.put("addressId",addressId); cart.put("deliveryFee",quote.deliveryFee()); cart.put("discount",quote.discount()); cart.put("total",quote.total()); cart.put("maxAmount",maximum); cart.put("authorizationMode",mode.name());
        return new AgentResult(result.status(),result.message(),result.nextAction(),cart,result.events(),result.error(),result.eventsRecorded());
    }
// 处理 `bindAuthorizedCandidate` 相关逻辑。
    private void bindAuthorizedCandidate(Long userId, String sessionId, AgentResult result) {
        if (result.status()!=AgentResult.Status.AWAITING_CONFIRMATION) return;
        Long cartId=number(result.candidateCart().get("cartDraftId")); Long addressId=number(result.candidateCart().get("addressId")); BigDecimal total=decimal(result.candidateCart().get("total"));
        Object workflow=result.candidateCart().get("workflowId");
        if(cartId!=null&&addressId!=null&&total!=null&&workflow!=null) sessions.bindAuthorizedCart(userId,sessionId,String.valueOf(workflow),cartId,addressId,total);
    }
// 处理 `number` 相关逻辑。
    private Long number(Object value){if(value instanceof Number n)return n.longValue();try{return value==null?null:Long.valueOf(String.valueOf(value));}catch(NumberFormatException ignored){return null;}}
// 处理 `decimal` 相关逻辑。
    private BigDecimal decimal(Object value){if(value instanceof BigDecimal b)return b;if(value instanceof Number n)return new BigDecimal(n.toString());try{return value==null?null:new BigDecimal(String.valueOf(value));}catch(NumberFormatException ignored){return null;}}
// 判断confirmation。
    private boolean isConfirmation(String text) { String normal=text == null ? "" : text.replace(" ", ""); return normal.equals("确认") || normal.equals("确认下单") || normal.equals("确定") || normal.equals("确定下单"); }
}
