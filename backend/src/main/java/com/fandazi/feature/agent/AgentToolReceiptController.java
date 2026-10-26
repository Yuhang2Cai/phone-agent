// 执行回查内部HTTP模块：专用短时JWT与完整原scope绑定，不能按客户端SQL或任意账号查库。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 只读回查不在模型17工具集合中；当前焦点变化不撤销本人原执行事实的可读性。 */
@RestController @RequestMapping("/internal/v2/task-executions")
public class AgentToolReceiptController {
    private AgentRequestAdmission admission=AgentRequestAdmission.disabled();
    /** 原键回执使用控制保留通道，普通输入饱和不阻断未知写的事实核查。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachAdmission(AgentRequestAdmission admission) {this.admission=admission;}
    private final InternalV1JwtValidator jwt;private final AgentTaskReceiptService receipts;
    /** 注入专用内部鉴权及无业务写依赖的回查服务。 */
    public AgentToolReceiptController(InternalV1JwtValidator jwt,AgentTaskReceiptService receipts){this.jwt=jwt;this.receipts=receipts;}
    /** 固定参数只用于归属/精确键查找；未知结果明确unknown，不触发工具或新确认。 */
    @GetMapping("/{executionKey}") public ResponseEntity<Map<String,Object>> read(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable String executionKey,@RequestParam String userId,@RequestParam String sessionId,@RequestParam String conversationEpoch,
            @RequestParam String conversationTaskId,@RequestParam String runId,@RequestParam String turnId){
        try{
            jwt.validate(authorization,userId);
            // 准入前只核身份，准入内再读结果；不在无许可时执行完整回查。
            if(admission.enabled())receipts.preflight(Long.valueOf(userId),sessionId,conversationEpoch,conversationTaskId,runId,turnId,executionKey);
            try(var permit=admission.acquire(userId,true)) {
                return ResponseEntity.ok(receipts.read(Long.valueOf(userId),sessionId,conversationEpoch,conversationTaskId,runId,turnId,executionKey));
            }
        }
        catch(ToolBoundaryException denied){return ResponseEntity.status("NOT_FOUND".equals(denied.getCode())?404:"VALIDATION_ERROR".equals(denied.getCode())?400:403).body(error(denied.getCode()));}
        catch(ResponseStatusException denied){return ResponseEntity.status(denied.getStatusCode()).body(error("NOT_FOUND"));}
        catch(NumberFormatException invalid){return ResponseEntity.status(403).body(error("FORBIDDEN"));}
    }
    /** 错误消息固定，不返回执行记录正文、数据库异常或授权配置。 */
    private Map<String,Object> error(String code){return Map.of("schemaVersion","2.2","error",Map.of("code",code,"message","无法读取该执行范围","retryable",false));}
}
