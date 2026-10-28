package com.fandazi.feature;

import com.fandazi.feature.order.OrderConflictException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Spring业务错误出口：保留领域、订单冲突及参数错误的既有状态码和公开字段。
 * 领域错误也可能在SSE订阅建立之前抛出，必须明确JSON出口，避免依赖成功流的媒体类型协商。
 */
@RestControllerAdvice
public class DomainExceptionHandler {
    /** 上游拒绝时Spring可能已保存输入与turn；不伪造业务成功，也不谎称整个请求没有持久记录。 */
    @ExceptionHandler(com.fandazi.feature.agent.AgentUpstreamAdmissionException.class)
    ResponseEntity<Map<String,String>> upstreamAdmission(com.fandazi.feature.agent.AgentUpstreamAdmissionException exception) {
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_JSON)
            .header("Retry-After",String.valueOf(exception.retryAfter()))
            .body(Map.of("code",exception.status()==429?"RATE_LIMITED":"SERVICE_UNAVAILABLE","error",exception.getMessage()));
    }
    /** 准入拒绝使用现有code/error信封；未受理不发业务ID，Retry-After不授权自动重发写。 */
    @ExceptionHandler(com.fandazi.feature.agent.AgentRequestAdmission.Denied.class)
    ResponseEntity<Map<String,String>> admission(com.fandazi.feature.agent.AgentRequestAdmission.Denied exception) {
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_JSON)
            .header("Retry-After",String.valueOf(exception.retryAfter()))
            .body(Map.of("code",exception.status()==429?"RATE_LIMITED":"SERVICE_UNAVAILABLE","error","请求暂未受理，请稍后重试"));
    }
    /**
     * 将领域异常转换为既有400响应；输入消息为空时采用公开兜底文案，不暴露栈或内部配置。
     * SSE客户端的Accept不能把此错误再次转换成不可表示响应，故明确指定JSON内容类型。
     */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, String>> handle(DomainException exception) {
        // 只保留原有明确前缀分类，不因SSE或页面入口改变业务错误码和状态码。
        String message = exception.getMessage();
        String code = message != null && message.startsWith("VALIDATION_ERROR:")
                ? "VALIDATION_ERROR" : "DOMAIN_ERROR";
        // 订阅未建立时尚无事件流，使用普通JSON错误；成功SSE和实际流事件完全不经过此处理器。
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "error", message == null ? "请求失败" : message));
    }

    /** 将订单状态冲突映射为既有409与公开文案，保持交易并发语义。 */
    @ExceptionHandler(OrderConflictException.class)
    ResponseEntity<Map<String, String>> conflict(OrderConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CONFLICT", "error", exception.getMessage()));
    }

    /** 参数/方法校验失败统一返回既有400，不把内部字段或校验栈交给客户端。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class})
    ResponseEntity<Map<String, String>> validation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "VALIDATION_ERROR", "error", "请求参数无效"));
    }
}
