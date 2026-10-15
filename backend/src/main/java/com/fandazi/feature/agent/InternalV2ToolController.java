// 新工具HTTP入口：专用短期JWT→完整v2Schema→权威栅栏桥，业务/审计提交彼此隔离。
package com.fandazi.feature.agent;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 新传输仍仅17业务工具；支付重准备和执行回查走内部管理端点，不增加模型工具。 */
@RestController @RequestMapping("/internal/v2/tools")
public class InternalV2ToolController {
    private static final Logger LOG=LoggerFactory.getLogger(InternalV2ToolController.class);
    private final InternalV1JwtValidator jwt;private final AgentToolSchemaValidator schema;private final AgentTaskToolBridge bridge;
    private final AgentV1ToolAudit audit;private final ObjectMapper mapper;private final boolean enabled;private final boolean goalEnabled;
    /** feature默认关闭；内部JWT与用户JWT互不替代，任何输入不能开启新工具路径。 */
    public InternalV2ToolController(InternalV1JwtValidator jwt,AgentToolSchemaValidator schema,AgentTaskToolBridge bridge,
            AgentV1ToolAudit audit,ObjectMapper mapper,@Value("${agent.task-lifecycle.enabled:false}") boolean enabled,
            @Value("${agent.goal-recovery.enabled:false}") boolean goalEnabled){
        this.jwt=jwt;this.schema=schema;this.bridge=bridge;this.audit=audit;this.mapper=mapper;this.enabled=enabled;this.goalEnabled=goalEnabled;
    }
    /** 原始Map先验证避免Jackson忽略未知身份/价格字段；错误只回稳定安全结构。 */
    @PostMapping("/{toolName}") public ResponseEntity<Map<String,Object>> call(@PathVariable String toolName,
            @RequestHeader(value="Authorization",required=false) String authorization,@RequestBody(required=false) Map<String,Object> raw){
        InternalV2ToolRequest request=null;Map<String,Object> result;int status=200;
        try {
            jwt.validate(authorization,raw!=null&&raw.get("userId") instanceof String user?user:"");
            if(!enabled)return ResponseEntity.status(503).body(failure("2.0","DEPENDENCY_UNAVAILABLE","任务工具协议尚未启用"));
            String version=version(raw);
            if("2.1".equals(version)) {
                if(!goalEnabled)return ResponseEntity.status(503).body(failure("2.1","DEPENDENCY_UNAVAILABLE","目标工具协议尚未启用"));
                schema.validateV21Request(toolName,raw);
            } else schema.validateV2Request(toolName,raw);
            request=mapper.convertValue(raw,InternalV2ToolRequest.class);
            var data=bridge.execute(toolName,request);result=new LinkedHashMap<>();result.put("schemaVersion",version);result.put("ok",true);result.put("data",data);result.put("error",null);
            if("2.1".equals(version))schema.validateV21Result(result);else schema.validateV2Result(result);
        } catch(ToolBoundaryException denied){result=failure(version(raw),denied.getCode(),denied.getMessage());status="FORBIDDEN".equals(denied.getCode())?403:"NOT_FOUND".equals(denied.getCode())?404:200;}
        catch(DomainException denied){result=failure(version(raw),"CONFLICT","业务条件已变化，请查询事实后重新确认");}
        catch(ArithmeticException|IllegalArgumentException invalid){result=failure(version(raw),"VALIDATION_ERROR","请求字段超出业务支持范围");}
        catch(org.springframework.dao.DataIntegrityViolationException conflict){result=failure(version(raw),"CONFLICT","业务资源发生冲突，请查询事实");}
        catch(Exception failure){LOG.error("任务工具失败：tool={}，异常类型={}",toolName,failure.getClass().getSimpleName());result=failure(version(raw),"INTERNAL_ERROR","工具暂时无法完成，请稍后核查");}
        // 业务已提交才另行记录公开审计，审计失败不把已完成业务误报为工具失败。
        if(request!=null)try{audit.recordTask(request,toolName,result);}catch(Exception failed){LOG.error("任务工具审计失败：tool={}，runId={}，异常类型={}",toolName,request.runId(),failed.getClass().getSimpleName());}
        return ResponseEntity.status(status).body(result);
    }
    /** 错误JSON仍返回固定新信封，不能泄漏框架或SQL正文。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(failure("2.0","VALIDATION_ERROR","工具请求必须为合法JSON对象"));}
    /** 错误响应尽量保持请求信封版本；未知版本按2.0返回且仍会被调用方拒绝。 */
    private String version(Map<String,Object> raw){return raw!=null&&"2.1".equals(raw.get("schemaVersion"))?"2.1":"2.0";}
    /** 失败数据为空；结果不明/冲突不能标成可盲目重发业务写。 */
    private Map<String,Object> failure(String version,String code,String message){return Map.of("schemaVersion",version,"ok",false,"data",Map.of(),"error",
        Map.of("code",code,"message",message,"retryable",Set.of("DEPENDENCY_UNAVAILABLE","INTERNAL_ERROR","RATE_LIMITED").contains(code)));}
}
