// 智能助手网关：绑定用户会话、授权和后端代理调用。
package com.fandazi.feature.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service public class AgentGatewayService {
    private final AgentSessionService sessions; private final AgentServiceClient client; private final AgentEventService events; private final AgentCardEnrichmentService cards;
    private AgentHistoryRecordingService messages;
    private AgentCompareContextService comparisons;
    /** 生产装配只读可信对比服务；旧测试不带对比字段时继续原调用路径。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachComparisons(AgentCompareContextService comparisons){this.comparisons=comparisons;}

    /** 生产Spring注入记录服务；保留旧四参领域测试构造器，不更改Agent模型协议。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachMessages(AgentHistoryRecordingService messages) { this.messages=messages; }
// 初始化对象依赖与默认状态。
    public AgentGatewayService(AgentSessionService sessions, AgentServiceClient client, AgentEventService events, AgentCardEnrichmentService cards) { this.sessions=sessions; this.client=client; this.events=events; this.cards=cards; }
// 处理当前数据。
    public AgentResult handle(Long userId,String sessionId,String text,AgentMode mode) {
        return handle(userId,sessionId,text,mode,null);
    }

    /** 可选展示键仅关联用户气泡；合法输入先记录，业务执行与消息记录错误分别处理。 */
    public AgentResult handle(Long userId,String sessionId,String text,AgentMode mode,String clientMessageKey) {
        return handle(userId,sessionId,text,mode,clientMessageKey,null);
    }

    /** 可选对比引用在任务建立/历史写入之前校验并重读；对比只读，不赋予确认权限。 */
    public AgentResult handle(Long userId,String sessionId,String text,AgentMode mode,String clientMessageKey,List<String> compareDealIds) {
        if(text==null || text.isBlank())throw new com.fandazi.feature.DomainException("消息不能为空");
        if(clientMessageKey!=null && !clientMessageKey.matches("[A-Za-z0-9_-]{1,100}"))throw new com.fandazi.feature.DomainException("消息展示键无效");
        // 非法比较资源不能触发模型、创建任务或记录一半用户输入；普通消息保留原行为。
        if(compareDealIds!=null && comparisons==null)throw new com.fandazi.feature.DomainException("对比服务暂不可用");
        var trustedContext=new java.util.LinkedHashMap<String,Object>();
        if(compareDealIds!=null)trustedContext.putAll(comparisons.build(compareDealIds));
        trustedContext.put("confirmationAccepted",compareDealIds==null && mode==AgentMode.CONFIRM && isConfirmation(text));
        String traceId=UUID.randomUUID().toString(); AgentSession session=sessions.start(userId,sessionId,mode,traceId);
        // 会话实体可能被交错请求更新，必须在外部调用前冻结本轮身份，不能返回后再读取实体。
        String runId = session.getActiveRunId();
        // 写入完整合法输入；记录故障由独立服务标记，不阻断模型或重执业务。
        if(messages!=null)messages.recordUser(userId,sessionId,runId,traceId,text,clientMessageKey);
        if (compareDealIds==null && mode == AgentMode.CONFIRM && isConfirmation(text)) sessions.confirm(userId, sessionId);
        // 网关兼容事件也绑定真实输入链路，防止迟到的上一轮兼容终态停止当前轮动画；不伪造原事件身份。
        events.record(userId, sessionId, "parsed", "已接收团购助手请求", null, "running", Map.of("runId", runId, "traceId", traceId));
        try {
            // 将用户身份、会话和模式作为 Spring 已校验的上下文传给独立 Agent，模型不能自行伪造这些字段。
            AgentResult result=client.run(new AgentRunRequest(userId.toString(),sessionId,text,
                    mode == AgentMode.THIS_TASK_AUTO ? "this_task_auto" : "confirm",traceId,
                    runId, trustedContext));
            // 模型只提交公开 ID；网关在返回 Android 前重新查询真实资源，防止客户端展示过期或伪造字段。
            result = enrichCards(userId, sessionId, result);
            // 只接受新团购摘要；旧购物车/地址确认被明确拒绝，不再重查或授权外卖交易。
            if (result.candidateCart().containsKey("cartDraftId") || result.candidateCart().containsKey("addressId"))
                result = AgentResult.failed("旧外卖动作已停用，请使用团购服务");
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
            // 终态只保存服务端实际完整回复，不把事件摘要或历史按钮当成用户输入。
            if(messages!=null)messages.recordAssistant(userId,sessionId,runId,traceId,result.message());
            return result;
        } catch (AgentServiceUnavailableException exception) {
            sessions.finishRun(userId, sessionId, runId, AgentResult.Status.FAILED);
            events.record(userId,sessionId,"failed",exception.getMessage(),null,"retryable",Map.of("runId", runId, "traceId", traceId));
            AgentResult failure=AgentResult.failed(exception.getMessage());
            if(messages!=null)messages.recordAssistant(userId,sessionId,runId,traceId,failure.message());
            return failure;
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
// 判断confirmation。
    // 本轮确认使用整句匹配；支付必须是单独输入，不能从订单创建成功推断。
    private boolean isConfirmation(String text) { String normal=text == null ? "" : text.trim(); return normal.equals("确认") || normal.equals("确认下单") || normal.equals("确定") || normal.equals("确定下单") || normal.equals("确认退款") || normal.equals("确认投诉") || normal.equals("确认支付") || normal.equals("确认模拟支付"); }
}
