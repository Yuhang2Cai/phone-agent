// GR-02内部目标版本入口：接收Agent已持久候选并在Spring短事务中登记，接口不进入模型工具目录。
package com.fandazi.feature.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 提供目标版本提交与原请求回查。账号使用短时内部JWT绑定，任务归属和三版本仍由服务层在行锁内核验。
 * 控制器只做传输身份拆分，不能自行激活Agent候选、执行业务工具或签发确认凭证。
 */
@RestController
@RequestMapping("/internal/v2/task-goals/revisions")
public class AgentGoalRevisionController {
    private static final Set<String> BODY_FIELDS=Set.of("userId","sessionId","conversationTaskId","schemaVersion",
            "requestId","candidateHash","expectedGoalRevision","expectedPlanRevision","expectedEffectVersion",
            "goalRevision","planRevision","projection","taskFence");
    private final InternalV1JwtValidator jwt;
    private final AgentGoalService goals;
    private final boolean enabled;

    /** 功能开关默认关闭；即使JWT合法，请求正文也不能开启2.3目标写入。 */
    public AgentGoalRevisionController(InternalV1JwtValidator jwt,AgentGoalService goals,
            @Value("${agent.goal-recovery.enabled:false}") boolean enabled) {
        this.jwt=jwt;this.goals=goals;this.enabled=enabled;
    }

    /** 精确校验传输字段，移除可信路由身份后把候选交给单个Spring事务提交。 */
    @PostMapping
    public ResponseEntity<Map<String,Object>> commit(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestBody(required=false) Map<String,Object> raw) {
        if(raw==null||!raw.keySet().equals(BODY_FIELDS))return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));
        Object user=raw.get("userId"),session=raw.get("sessionId"),task=raw.get("conversationTaskId");
        if(!(user instanceof String userId)||userId.isBlank()||!(session instanceof String sessionId)||sessionId.isBlank()
                ||!(task instanceof String taskId)||taskId.isBlank())return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));
        try {
            jwt.validate(authorization,userId);
            if(!enabled)return ResponseEntity.status(503).body(error("DEPENDENCY_UNAVAILABLE"));
            // 服务请求不得保留传输身份字段，避免未知字段被宽松忽略或覆盖已鉴权作用域。
            var request=new LinkedHashMap<String,Object>(raw);request.remove("userId");request.remove("sessionId");request.remove("conversationTaskId");
            return ResponseEntity.ok(goals.commitRevision(Long.valueOf(userId),sessionId,taskId,request));
        } catch(ToolBoundaryException denied) {
            return ResponseEntity.status("FORBIDDEN".equals(denied.getCode())?403:409).body(error(denied.getCode()));
        } catch(org.springframework.web.server.ResponseStatusException denied) {
            // 服务层CAS和归属错误必须保留HTTP状态及稳定代码，不能落入含框架正文的500错误页。
            int status=denied.getStatusCode().value();String code=status==409?"CONFLICT":status==404?"NOT_FOUND":"VALIDATION_ERROR";
            return ResponseEntity.status(status).body(error(code));
        } catch(NumberFormatException invalid) {
            return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));
        }
    }

    /** 只读原requestId权威回执；丢失ACK时必须查询同一请求，不能换新身份重新提交。 */
    @GetMapping("/{requestId}")
    public ResponseEntity<Map<String,Object>> read(@PathVariable String requestId,
            @RequestParam String userId,@RequestParam String sessionId,@RequestParam String conversationTaskId,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        try {
            jwt.validate(authorization,userId);
            if(!enabled)return ResponseEntity.status(503).body(error("DEPENDENCY_UNAVAILABLE"));
            return ResponseEntity.ok(goals.revision(Long.valueOf(userId),sessionId,conversationTaskId,requestId));
        } catch(ToolBoundaryException denied) {
            return ResponseEntity.status("FORBIDDEN".equals(denied.getCode())?403:409).body(error(denied.getCode()));
        } catch(org.springframework.web.server.ResponseStatusException denied) {
            // 回查不存在与作用域不符统一404，调用方不能据差异枚举其他账号的requestId。
            int status=denied.getStatusCode().value();String code=status==409?"CONFLICT":status==404?"NOT_FOUND":"VALIDATION_ERROR";
            return ResponseEntity.status(status).body(error(code));
        } catch(NumberFormatException invalid) {
            return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));
        }
    }

    /** 损坏JSON只返回稳定代码，不回显请求正文、数据库异常或内部凭证。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}

    /** 内部失败信封仅供Agent分类；具体冲突由全局ResponseStatusException处理器保持HTTP状态。 */
    private Map<String,Object> error(String code){return Map.of("schemaVersion","2.3","error",Map.of("code",code,"message","目标版本请求未提交","retryable",false));}
}
