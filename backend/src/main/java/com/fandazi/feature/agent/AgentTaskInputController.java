// 2.2显式协商输入入口：旧/messages行为保留，客户端不能以配置或聊天文字静默升级旧活动run。
package com.fandazi.feature.agent;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Android仍只调用Spring；任何Agent归属/工具内部接口都不直接向客户端开放。 */
@RestController
@RequestMapping("/api/agent/sessions/{sessionId}/task-inputs")
public class AgentTaskInputController {
    private final AgentTaskGatewayService gateway;
    /** 只注入新任务网关；任务命令和普通受理在服务中按不同出口处理。 */
    public AgentTaskInputController(AgentTaskGatewayService gateway){this.gateway=gateway;}
    /** 版本2.2必须显式出现在请求，非法附加字段在任何会话/任务变更前拒绝。 */
    @PostMapping public AgentResult input(@AuthenticationPrincipal Long user,@PathVariable String sessionId,@RequestBody Map<String,Object> raw) {
        return gateway.handle(user,sessionId,raw);
    }
}
