// 智能助手模块：提供接口并将请求交给业务层。
package com.fandazi.feature.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @RequestMapping("/api/agent/sessions") public class AgentGatewayController {
    /** 可选展示键只关联气泡身份；旧客户端仍可发送原text/mode，不提供任何交易权限。 */
    public record MessageRequest(@NotBlank String text,@NotNull AgentMode mode,
            @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{1,100}") String clientMessageKey, java.util.List<String> compareDealIds,
            java.util.Map<String,Object> selectionRef) {
        /** 保留原两参数构造器供旧调用，展示键缺失不拒绝正常消息。 */
        public MessageRequest(String text,AgentMode mode) { this(text,mode,null,null,null); }
        /** 原三字段构造器保持兼容；比较ID由独立可空字段表示，禁止从text修改或提取价格。 */
        public MessageRequest(String text,AgentMode mode,String clientMessageKey){this(text,mode,clientMessageKey,null,null);}
        /** 保留比较入口原构造，旧客户端省略引用时继续保守处理。 */
        public MessageRequest(String text,AgentMode mode,String clientMessageKey,java.util.List<String> compareDealIds){this(text,mode,clientMessageKey,compareDealIds,null);}
    }
    /** Android 公开事件 DTO；历史事件同样经过脱敏，避免部署前的旧数据在重连时泄漏凭证。 */
    public record EventResponse(Long id,String type,String summary,String toolName,String status,java.util.Map<String,Object> payload) {
        /** 实时发送与历史重放使用同一出口，不把数据库载荷直接暴露给客户端。 */
        static EventResponse from(AgentEventEntity event) {
            return new EventResponse(event.getId(), event.getType(), event.getSummary(), event.getToolName(), event.getStatus(),
                    AgentContractCompatibility.publicEventPayload(event.getPayload()));
        }
    }
    private final AgentGatewayService gateway; private final AgentSessionService sessions; private final AgentEventService events;
    private AgentHistoryService history;
    /** 生产路径注入私有历史读取；保留原构造器，不改变旧消息与SSE测试入口。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachHistory(AgentHistoryService history) { this.history=history; }
// 初始化对象依赖与默认状态。
    public AgentGatewayController(AgentGatewayService gateway,AgentSessionService sessions,AgentEventService events){this.gateway=gateway;this.sessions=sessions;this.events=events;}
// 处理 `message` 相关逻辑。
    @PostMapping("/{sessionId}/messages") public AgentResult message(@AuthenticationPrincipal Long userId,@PathVariable String sessionId,@Valid @RequestBody MessageRequest request){return gateway.handle(userId,sessionId,request.text(),request.mode(),request.clientMessageKey(),request.compareDealIds(),request.selectionRef());}
// 处理 `stream` 相关逻辑。
    @GetMapping(value="/{sessionId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@AuthenticationPrincipal Long userId,@PathVariable String sessionId,@RequestHeader(value="Last-Event-ID",required=false) Long lastEventId){sessions.owned(userId,sessionId);return events.subscribe(userId,sessionId,lastEventId,EventResponse::from);}
    /** 当前账户只读恢复V1历史；默认50条，非法limit或跨资源游标由服务明确拒绝。 */
    @GetMapping("/{sessionId}/history")
    public AgentHistoryResponse history(@AuthenticationPrincipal Long userId,@PathVariable String sessionId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return history.page(userId,sessionId,cursor,limit);
    }
}
