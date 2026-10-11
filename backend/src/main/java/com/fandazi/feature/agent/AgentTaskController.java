// 私有任务目录HTTP模块：账号来自安全链主体，仅提供只读投影，不启动/恢复/准备/执行业务。
package com.fandazi.feature.agent;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Android仍只访问Spring；任务ID和sessionId不能替代账号归属核验。 */
@RestController @RequestMapping("/api/agent/sessions/{sessionId}/tasks")
public class AgentTaskController {
    private final AgentTaskRegistryService registry;
    /** 注入持久目录服务，不注入模型/工具或任何写入客户端。 */
    public AgentTaskController(AgentTaskRegistryService registry) { this.registry=registry; }
    /** 获取同一快照的当前/暂停目录与事件水位；跨用户/不存在统一404。 */
    @GetMapping public Map<String,Object> directory(@AuthenticationPrincipal Long user,@PathVariable String sessionId) {
        return registry.directory(user,sessionId);
    }
}
