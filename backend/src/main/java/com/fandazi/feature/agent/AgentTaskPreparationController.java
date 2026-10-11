// 参数纠正管理入口：固定内部JWT、完整任务栅栏及字段白名单；不向模型注册业务工具。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 撤销旧准备后才允许生成新确认；网络重试由同库私有回执去重，不延长任何旧凭证期限。 */
@RestController @RequestMapping("/internal/v2/task-preparations")
public class AgentTaskPreparationController {
    private final InternalV1JwtValidator jwt;private final AgentTaskFenceService service;private final boolean enabled;
    /** 注入内部鉴权和默认关闭开关；请求不能自行开启2.2能力。 */
    public AgentTaskPreparationController(InternalV1JwtValidator jwt,AgentTaskFenceService service,
            @Value("${agent.task-lifecycle.enabled:false}") boolean enabled){this.jwt=jwt;this.service=service;this.enabled=enabled;}
    /** JWT主体须与完整fence账号一致；授权/栅栏/载荷异常均在领域准备及业务写入前拒绝。 */
    @PostMapping("/invalidate") public ResponseEntity<Map<String,Object>> invalidate(
            @RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Map<String,Object> raw){
        try {
            Object user=null;if(raw!=null&&raw.get("fence") instanceof Map<?,?> fence&&fence.get("scope") instanceof Map<?,?> scope)user=scope.get("userId");
            jwt.validate(authorization,user instanceof String id?id:"");
            if(!enabled)return ResponseEntity.status(503).body(error("DEPENDENCY_UNAVAILABLE"));
            return ResponseEntity.ok(service.invalidatePreparation(raw));
        } catch(ToolBoundaryException denied){return ResponseEntity.status("FORBIDDEN".equals(denied.getCode())?403:"VALIDATION_ERROR".equals(denied.getCode())?400:409).body(error(denied.getCode()));}
        catch(IllegalArgumentException invalid){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    }
    /** JSON损坏时返回固定错误，不泄漏框架或数据库异常。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    /** 只提供稳定错误码，内部凭证及用户正文不进入错误响应。 */
    private Map<String,Object> error(String code){return Map.of("schemaVersion","2.2","error",Map.of("code",code,"message","无法撤销原确认准备","retryable",false));}
}
