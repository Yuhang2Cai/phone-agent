// V1 内部工具入口模块：严格校验 JWT 和权威契约，成功交给真实领域，失败返回稳定且安全的工具结果。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.DomainException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 仅公开 17 个工具；业务拒绝以标准结果返回，鉴权失败使用 403，缺少工具使用 404。 */
@RestController
@RequestMapping("/internal/v1/tools")
public class InternalV1ToolController {
    private static final Logger log = LoggerFactory.getLogger(InternalV1ToolController.class);
    private final InternalV1JwtValidator jwt;
    private final AgentToolSchemaValidator schema;
    private final AgentV1ToolBridge bridge;
    private final AgentV1ToolAudit audit;
    private final ObjectMapper mapper;
    /** 注入鉴权、结构校验、真实执行与隔离审计；旧静态令牌控制器不会参与本路径。 */
    InternalV1ToolController(InternalV1JwtValidator jwt, AgentToolSchemaValidator schema, AgentV1ToolBridge bridge,
                             AgentV1ToolAudit audit, ObjectMapper mapper) {
        this.jwt = jwt; this.schema = schema; this.bridge = bridge; this.audit = audit; this.mapper = mapper;
    }
    /** 完整 raw 对象先验证再转 DTO；错误消息不得包含原请求、SQL、异常类型或堆栈。 */
    @PostMapping("/{toolName}")
    public ResponseEntity<Map<String, Object>> call(@PathVariable String toolName,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> raw) {
        InternalV1ToolRequest request = null;
        Map<String, Object> result;
        int status = 200;
        try {
            // 输入校验：身份必须以原字符串出现；令牌无效时不查询业务数据或泄漏白名单判断。
            jwt.validate(authorization, raw != null && raw.get("userId") instanceof String userId ? userId : "");
            schema.validateRequest(toolName, raw);
            request = mapper.convertValue(raw, InternalV1ToolRequest.class);
            result = success(bridge.execute(toolName, request));
            schema.validateResult(result);
        } catch (ToolBoundaryException exception) {
            result = failure(exception.getCode(), exception.getMessage());
            status = "FORBIDDEN".equals(exception.getCode()) ? 403 : "NOT_FOUND".equals(exception.getCode()) ? 404 : 200;
        } catch (DomainException exception) {
            // 历史领域消息只用于选择稳定分类，公开文本使用预定义中文，不能回传异常原文。
            String code = domainCode(exception.getMessage());
            result = failure(code, safeMessage(code));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            result = failure("VALIDATION_ERROR", "请求字段超出业务支持范围");
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            result = failure("CONFLICT", "动作或业务资源发生冲突，请查询当前事实");
        } catch (Exception exception) {
            log.error("V1 tool failed: tool={}, exceptionType={}", toolName, exception.getClass().getSimpleName(), exception);
            result = failure("INTERNAL_ERROR", "工具暂时无法完成，请稍后重试");
        }
        // 审计在业务提交之后单独运行；失败只记录动作链路，不能把已完成业务误报成失败。
        if (request != null) {
            try { audit.record(request, toolName, result); }
            catch (Exception exception) { log.error("V1 tool audit failed: tool={}, runId={}, traceId={}", toolName, request.runId(), request.traceId(), exception); }
        }
        return ResponseEntity.status(status).body(result);
    }
    /** 非法 JSON 也转换为工具结果，避免框架默认错误页面泄漏不同结构。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformedBody() { return ResponseEntity.badRequest().body(failure("VALIDATION_ERROR", "工具请求必须为合法 JSON 对象")); }
    /** 成功结构保留 null error，Map.of 不能表达合法的 null 协议值。 */
    private Map<String, Object> success(Map<String, Object> data) {
        var result = new LinkedHashMap<String, Object>(); result.put("schemaVersion", "1.1"); result.put("ok", true);
        result.put("data", data); result.put("error", null); return result;
    }
    /** 失败不夹带业务数据；仅依赖不可用和内部暂态错误提示可重试，业务冲突需要先查询事实。 */
    private Map<String, Object> failure(String code, String message) {
        return Map.of("schemaVersion", "1.1", "ok", false, "data", Map.of(), "error",
                Map.of("code", code, "message", message, "retryable", Set.of("DEPENDENCY_UNAVAILABLE", "INTERNAL_ERROR", "RATE_LIMITED").contains(code)));
    }
    /** 统一映射旧领域拒绝，优先识别显式码，然后识别资源缺失、授权、期限和状态冲突。 */
    private String domainCode(String message) {
        if (message == null) return "CONFLICT";
        for (String code : List.of("VALIDATION_ERROR", "NOT_FOUND", "FORBIDDEN", "CONFLICT", "EXPIRED", "RATE_LIMITED", "DEPENDENCY_UNAVAILABLE"))
            if (message.startsWith(code + ":")) return code;
        if (message.contains("不存在")) return "NOT_FOUND";
        if (message.contains("不属于")) return "FORBIDDEN";
        if (message.contains("过期")) return "EXPIRED";
        if (message.contains("长度") || message.contains("不能为空") || message.contains("必须")) return "VALIDATION_ERROR";
        return "CONFLICT";
    }
    /** 只使用固定安全中文文案，不向 Agent 透露数据库或第三方依赖信息。 */
    private String safeMessage(String code) {
        return switch (code) { case "NOT_FOUND" -> "业务资源不存在"; case "FORBIDDEN" -> "无权访问该业务资源";
            case "EXPIRED" -> "业务资格或确认已过期"; case "VALIDATION_ERROR" -> "请求不符合业务规则";
            default -> "业务条件已变化，请查询事实后重新确认"; };
    }
}
