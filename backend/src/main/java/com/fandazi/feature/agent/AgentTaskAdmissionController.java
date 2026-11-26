// Agent内部轮次许可入口：专用短期JWT绑定账号，回读原受理或补交已生成结果，绝不重执工作流。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 固定管理端点不注册模型工具，读写事务由TurnService自行短时完成，不跨HTTP持锁。 */
@RestController @RequestMapping("/internal/v2/task-admissions")
public class AgentTaskAdmissionController {
    private final InternalV1JwtValidator jwt;private final AgentTaskTurnService turns;
    /** 注入现有内部JWT与权威持久轮次服务，不接受客户端或模型直接上传授权。 */
    public AgentTaskAdmissionController(InternalV1JwtValidator jwt,AgentTaskTurnService turns){this.jwt=jwt;this.turns=turns;}
    /** GET只读原turn，旧epoch/他人账号先拒绝；不会新建task/run、签grant或修改trace。 */
    @GetMapping("/{turnId}") public ResponseEntity<Map<String,Object>> read(@PathVariable String turnId,@RequestParam String userId,@RequestParam String sessionId,
            @RequestParam String conversationEpoch,@RequestHeader(value="Authorization",required=false) String authorization){
        try{jwt.validate(authorization,userId);return ResponseEntity.ok(turns.readAuthority(Long.valueOf(userId),sessionId,conversationEpoch,turnId));}
        catch(ToolBoundaryException denied){return ResponseEntity.status("FORBIDDEN".equals(denied.getCode())?403:409).body(error(denied.getCode()));}
        catch(NumberFormatException invalid){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    }
    /** POST只接受原turn完整完成结果；同结果补交幂等，异文/旧代次不覆盖当前投影。 */
    @PostMapping("/{turnId}/completion") public ResponseEntity<Map<String,Object>> complete(@PathVariable String turnId,@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestBody Map<String,Object> raw){
        try{
            Object user=null,session=null;if(raw!=null&&raw.get("fence") instanceof Map<?,?> fence&&fence.get("scope") instanceof Map<?,?> scope){user=scope.get("userId");session=scope.get("sessionId");}
            jwt.validate(authorization,user instanceof String id?id:"");
            if(!turnId.equals(raw.get("turnId"))||!(session instanceof String sessionId))return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));
            // 身份和原turn校验先完成，非法输入不被记录成一次完成事务；计时不跨越JWT验证。
            Long owner=Long.valueOf((String)user);
            String trace=raw.get("result") instanceof Map<?,?> result&&result.get("traceId") instanceof String value?value:turnId;
            // 在原短事务服务的代理外计时，包含真实提交等待；不改变结果、异常、事务或执行次数。
            // 关联键只由安全观测器摘要化，禁止把原请求正文或内部凭证写入日志。
            return ResponseEntity.ok(AgentStageMetrics.measure("spring.complete",trace,()->turns.complete(owner,sessionId,raw)));
        }catch(ToolBoundaryException denied){return ResponseEntity.status("FORBIDDEN".equals(denied.getCode())?403:409).body(error(denied.getCode()));}
        catch(NumberFormatException|AgentServiceUnavailableException invalid){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    }
    /** 损坏JSON明确拒绝，不回显框架异常或完整请求。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR"));}
    /** 所有显式错误只保留稳定安全信息；SQL/正文/授权不进入响应。 */
    private Map<String,Object> error(String code){return Map.of("schemaVersion","2.2","error",Map.of("code",code,"message","轮次许可暂不可用","retryable",false));}
}
