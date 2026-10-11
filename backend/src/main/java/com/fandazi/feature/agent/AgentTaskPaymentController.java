// 支付停点内部HTTP入口：短期JWT绑定fence账号；仅管理重准备，不开放模型业务工具或支付动作。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 私有grant只返回内部Agent；公开展示由后续完成事务单独投影summary。 */
@RestController @RequestMapping("/internal/v2/task-payments")
public class AgentTaskPaymentController {
    private final InternalV1JwtValidator jwt;private final AgentTaskPaymentPreparationService service;private final boolean enabled;
    /** 注入专用内部鉴权与默认关闭功能开关，不允许请求开启新协议。 */
    public AgentTaskPaymentController(InternalV1JwtValidator jwt,AgentTaskPaymentPreparationService service,@Value("${agent.task-lifecycle.enabled:false}") boolean enabled){this.jwt=jwt;this.service=service;this.enabled=enabled;}
    /** 完整结构在事务服务再次核验；鉴权失败不读取订单，业务冲突不新建订单或支付。 */
    @PostMapping("/reprepare") public ResponseEntity<Map<String,Object>> reprepare(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Map<String,Object> raw){
        try {
            Object user=null;if(raw!=null&&raw.get("fence") instanceof Map<?,?> fence&&fence.get("scope") instanceof Map<?,?> scope)user=scope.get("userId");
            jwt.validate(authorization,user instanceof String id?id:"");
            if(!enabled)return ResponseEntity.status(503).body(error("DEPENDENCY_UNAVAILABLE"));return ResponseEntity.ok(service.reprepare(raw));
        } catch(ToolBoundaryException denied){return ResponseEntity.status("FORBIDDEN".equals(denied.getCode())?403:"VALIDATION_ERROR".equals(denied.getCode())?400:409).body(error(denied.getCode()));}
        catch(ArithmeticException|IllegalArgumentException invalid){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    }
    /** 非对象/损坏JSON不得走到订单读取或框架异常页面。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    /** 固定安全拒绝信息，不向Agent泄漏数据库或授权配置。 */
    private Map<String,Object> error(String code){return Map.of("schemaVersion","2.2","error",Map.of("code",code,"message","无法重新准备原订单支付","retryable",false));}
}
