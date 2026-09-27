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
    public record MessageRequest(@NotBlank String text,@NotNull AgentMode mode) {}
// 处理 `from` 相关逻辑。
    public record EventResponse(Long id,String type,String summary,String toolName,String status,java.util.Map<String,Object> payload) { static EventResponse from(AgentEventEntity e){return new EventResponse(e.getId(),e.getType(),e.getSummary(),e.getToolName(),e.getStatus(),e.getPayload());} }
    private final AgentGatewayService gateway; private final AgentSessionService sessions; private final AgentEventService events;
// 初始化对象依赖与默认状态。
    public AgentGatewayController(AgentGatewayService gateway,AgentSessionService sessions,AgentEventService events){this.gateway=gateway;this.sessions=sessions;this.events=events;}
// 处理 `message` 相关逻辑。
    @PostMapping("/{sessionId}/messages") public AgentResult message(@AuthenticationPrincipal Long userId,@PathVariable String sessionId,@Valid @RequestBody MessageRequest request){return gateway.handle(userId,sessionId,request.text(),request.mode());}
// 处理 `stream` 相关逻辑。
    @GetMapping(value="/{sessionId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@AuthenticationPrincipal Long userId,@PathVariable String sessionId,@RequestHeader(value="Last-Event-ID",required=false) Long lastEventId){sessions.owned(userId,sessionId);return events.subscribe(userId,sessionId,lastEventId,EventResponse::from);}
}
