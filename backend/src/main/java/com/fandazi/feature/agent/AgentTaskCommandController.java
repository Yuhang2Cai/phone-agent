// 公开任务命令HTTP模块：账号来自JWT主体，五字段控制请求与普通消息受理分开。
package com.fandazi.feature.agent;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 新功能默认关闭；已存在命令状态可只读回看，不能由用户字段启用协议或选择Agent地址。 */
@RestController @RequestMapping("/api/agent/sessions/{sessionId}/task-commands")
public class AgentTaskCommandController {
    private final AgentTaskControlService controls;private final AgentTaskReconciliationService reconciliation;private final boolean enabled;
    /** 注入控制服务与事务外协调器；控制器本身不进入数据库长事务。 */
    public AgentTaskCommandController(AgentTaskControlService controls,AgentTaskReconciliationService reconciliation,
            @Value("${agent.task-lifecycle.enabled:false}") boolean enabled) {
        this.controls=controls;this.reconciliation=reconciliation;this.enabled=enabled;
    }
    /** 严格按钮控制：预备持久命令后进行一次HTTP交接，未收安全回执返回处理中状态。 */
    @PostMapping public Map<String,Object> command(@AuthenticationPrincipal Long user,@PathVariable String sessionId,@RequestBody Map<String,Object> raw) {
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"任务生命周期功能尚未启用");
        var prepared=controls.prepare(user,sessionId,raw);return reconciliation.process((String)prepared.get("commandId"),Instant.now());
    }
    /** 只读公开状态，不返回私有ACK、检查点、源正文或凭证。 */
    @GetMapping("/{commandId}") public Map<String,Object> read(@AuthenticationPrincipal Long user,@PathVariable String sessionId,@PathVariable String commandId) {
        return controls.read(user,sessionId,commandId);
    }
    /** 失去POST响应后只按原requestId查结果，不能用重发原业务文字实现恢复。 */
    @GetMapping("/by-request/{requestId}") public Map<String,Object> readRequest(@AuthenticationPrincipal Long user,@PathVariable String sessionId,@PathVariable String requestId) {
        return controls.readRequest(user,sessionId,requestId);
    }
    /** 冷启动列出本人当前epoch尚未终结的命令，供展示处理中或需要核查。 */
    @GetMapping public java.util.List<Map<String,Object>> pending(@AuthenticationPrincipal Long user,@PathVariable String sessionId) {
        return controls.pendingForSession(user,sessionId);
    }
    /** 重查相同持久命令，不创建新的用户输入或重派业务；未知事实保持待核查。 */
    @PostMapping("/{commandId}/recheck") public Map<String,Object> recheck(@AuthenticationPrincipal Long user,@PathVariable String sessionId,@PathVariable String commandId) {
        return reconciliation.recheck(user,sessionId,commandId);
    }
}
