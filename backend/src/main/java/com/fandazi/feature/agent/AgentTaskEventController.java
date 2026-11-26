// 公开任务事件补读模块：鉴权用户只读本人scope水位，不调用模型、控制或业务工具。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 目录throughTaskEventId与事件ID配套；410/409要求客户端重新取得目录快照。 */
@RestController @RequestMapping("/api/agent/sessions/{sessionId}/task-events")
public class AgentTaskEventController {
    private final AgentTaskEventOutbox outbox;
    /** 注入只读补读服务，不接受客户端指定账号或epoch。 */
    public AgentTaskEventController(AgentTaskEventOutbox outbox){this.outbox=outbox;}
    /** 有界读取当前scope事件；身份来自安全链，游标非法/缺口明确拒绝。 */
    @GetMapping public List<Map<String,Object>> after(@AuthenticationPrincipal Long user,@PathVariable String sessionId,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit){return outbox.read(user,sessionId,after,limit);}
}
