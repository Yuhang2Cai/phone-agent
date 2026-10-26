// AG04公开目标控制器：Android只读取本人投影并提交受控恢复动作，不接触内部修订或工具接口。
package com.fandazi.feature.agent;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 路径中的sessionId、taskId和requestId都只是资源引用，实际账号归属来自安全链主体。 */
@RestController
@RequestMapping("/api/agent/sessions/{sessionId}/tasks/{taskId}")
public class AgentGoalController {
    private AgentRequestAdmission admission=AgentRequestAdmission.disabled();
    /** 恢复受理不启动业务，使用同应用控制额度；下一轮业务仍走普通输入保护。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachAdmission(AgentRequestAdmission admission) {this.admission=admission;}
    private final AgentGoalRecoveryService recoveries;

    /** 注入唯一恢复服务，控制器不自行读取数据库、调用Agent或构造业务成功结果。 */
    public AgentGoalController(AgentGoalRecoveryService recoveries){this.recoveries=recoveries;}

    /** 返回当前真实公开目标投影；关闭恢复写开关时既有2.3历史仍可只读展示。 */
    @GetMapping("/goals")
    public Map<String,Object> goals(@AuthenticationPrincipal Long user,@PathVariable String sessionId,
            @PathVariable String taskId) {
        if(admission.enabled())recoveries.preflight(user,sessionId,taskId);
        try(var permit=admission.acquire(String.valueOf(user),true)) {return recoveries.projection(user,sessionId,taskId);}
    }

    /** 受理一次用户明确动作；HTTP 202只表示持久受理，不表示业务已完成。 */
    @PostMapping("/goal-recoveries")
    public ResponseEntity<Map<String,Object>> recover(@AuthenticationPrincipal Long user,@PathVariable String sessionId,
            @PathVariable String taskId,@RequestBody(required=false) Map<String,Object> request) {
        if(admission.enabled())recoveries.preflight(user,sessionId,taskId);
        try(var permit=admission.acquire(String.valueOf(user),true)) {
            return ResponseEntity.accepted().body(recoveries.accept(user,sessionId,taskId,request));
        }
    }

    /** 断网未知POST只查询原requestId回执；此GET不创建来源、轮次、工具调用或业务写。 */
    @GetMapping("/goal-recoveries/{requestId}")
    public Map<String,Object> recovery(@AuthenticationPrincipal Long user,@PathVariable String sessionId,
            @PathVariable String taskId,@PathVariable String requestId) {
        if(admission.enabled())recoveries.preflight(user,sessionId,taskId);
        try(var permit=admission.acquire(String.valueOf(user),true)) {return recoveries.read(user,sessionId,taskId,requestId);}
    }
}
