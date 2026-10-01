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
    private final AgentSessionService sessions; private final AgentServiceClient client; private final AgentEventService events; private final CartService carts; private final AddressService addresses; private final AgentCardEnrichmentService cards;
// 初始化对象依赖与默认状态。
    public AgentGatewayService(AgentSessionService sessions, AgentServiceClient client, AgentEventService events, CartService carts, AddressService addresses, AgentCardEnrichmentService cards) { this.sessions=sessions; this.client=client; this.events=events; this.carts=carts; this.addresses=addresses; this.cards=cards; }
// 处理当前数据。
    public AgentResult handle(Long userId,String sessionId,String text,AgentMode mode) {
        String traceId=UUID.randomUUID().toString(); AgentSession session=sessions.start(userId,sessionId,mode,traceId);
        // 会话实体可能被交错请求更新，必须在外部调用前冻结本轮身份，不能返回后再读取实体。
        String runId = session.getActiveRunId();
        if (mode == AgentMode.CONFIRM && isConfirmation(text)) sessions.confirm(userId, sessionId);
        // 网关兼容事件也绑定真实输入链路，防止迟到的上一轮兼容终态停止当前轮动画；不伪造原事件身份。
        events.record(userId, sessionId, "parsed", "已接收团购助手请求", null, "running", Map.of("runId", runId, "traceId", traceId));
        try {
            // 将用户身份、会话和模式作为 Spring 已校验的上下文传给独立 Agent，模型不能自行伪造这些字段。
            AgentResult result=client.run(new AgentRunRequest(userId.toString(),sessionId,text,
                    mode == AgentMode.THIS_TASK_AUTO ? "this_task_auto" : "confirm",traceId,
                    runId, Map.of("confirmationAccepted", mode == AgentMode.CONFIRM && isConfirmation(text))));
            // 模型只提交公开 ID；网关在返回 Android 前重新查询真实资源，防止客户端展示过期或伪造字段。
            result = enrichCards(userId, sessionId, result);
            result=completeConfirmation(userId, mode, result);
            bindAuthorizedCandidate(userId, sessionId, result);
            // 将最终公开且已重查的摘要绑定待确认动作，泛化“确认”只能授权该资源与输入正文。
            if (result.status() == AgentResult.Status.AWAITING_CONFIRMATION && result.candidateCart().containsKey("action"))
                sessions.bindPendingConfirmation(userId, sessionId, runId, result.candidateCart());
            // 先保存暂停/终态，下一次补充或确认才能继续使用同一任务标识。
            sessions.finishRun(userId, sessionId, runId, result.status());
            // 旧替身返回的事件仍由网关写入；V1 客户端已经逐行记录，避免同一事件重复审计。
            if (!result.eventsRecorded()) for (AgentResult.Event event: result.events()) events.record(userId,sessionId,event.type(),event.summary(),event.toolName(),event.status(),event.payload());
            String outcome = switch (result.status()) {
                case NEEDS_INPUT -> "input_required";
                case AWAITING_CONFIRMATION -> "confirmation_required";
                case FAILED -> "failed";
                case COMPLETED, CANCELLED -> "completed";
            };
            var outcomePayload = new java.util.LinkedHashMap<>(result.candidateCart());
            outcomePayload.put("runId", runId); outcomePayload.put("traceId", traceId);
            events.record(userId,sessionId,outcome,result.message(),null,result.status().name().toLowerCase(),outcomePayload);
            return result;
        } catch (AgentServiceUnavailableException exception) {
            sessions.finishRun(userId, sessionId, runId, AgentResult.Status.FAILED);
            events.record(userId,sessionId,"failed",exception.getMessage(),null,"retryable",Map.of("runId", runId, "traceId", traceId)); return AgentResult.failed(exception.getMessage());
        }
    }

    /** 逐张校验并补全 Agent 卡片；任何非法卡片都作为明确失败返回，禁止静态兜底。 */
    private AgentResult enrichCards(Long userId, String sessionId, AgentResult result) {
        try {
            // 卡片和确认摘要均重查本人事实，Agent 金额不能直接进入公开 DTO。
            List<Map<String, Object>> enriched = result.cards().stream().map(reference -> cards.enrich(userId, sessionId, reference)).toList();
            Map<String, Object> summary = cards.enrichSummary(userId, sessionId, result.candidateCart());
            return new AgentResult(result.status(), result.message(), result.nextAction(), summary, result.events(), enriched, result.error(), result.eventsRecorded());
        } catch (com.fandazi.feature.DomainException exception) {
            return AgentResult.failed("卡片或确认资源已失效，请重新查询");
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
        return new AgentResult(result.status(),result.message(),result.nextAction(),cart,result.events(),result.cards(),result.error(),result.eventsRecorded());
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
    // 本轮确认使用整句匹配；支付必须是单独输入，不能从订单创建成功推断。
    private boolean isConfirmation(String text) { String normal=text == null ? "" : text.trim(); return normal.equals("确认") || normal.equals("确认下单") || normal.equals("确定") || normal.equals("确定下单") || normal.equals("确认退款") || normal.equals("确认投诉") || normal.equals("确认支付") || normal.equals("确认模拟支付"); }
}
