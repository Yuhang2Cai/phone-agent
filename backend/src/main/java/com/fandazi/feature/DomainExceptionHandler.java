// 应用模块：定义该模块的数据与行为。
package com.fandazi.feature;
import com.fandazi.feature.order.OrderConflictException; import jakarta.validation.ConstraintViolationException; import java.util.Map; import org.springframework.http.*; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.bind.annotation.*; import org.springframework.web.method.annotation.HandlerMethodValidationException;
// 处理当前数据。
// 处理 `conflict` 相关逻辑。
// 处理 `validation` 相关逻辑。
@RestControllerAdvice public class DomainExceptionHandler { @ExceptionHandler(DomainException.class) ResponseEntity<Map<String,String>> handle(DomainException exception){return ResponseEntity.badRequest().body(Map.of("error",exception.getMessage()));} @ExceptionHandler(OrderConflictException.class) ResponseEntity<Map<String,String>> conflict(OrderConflictException exception){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",exception.getMessage()));} @ExceptionHandler({MethodArgumentNotValidException.class,HandlerMethodValidationException.class,ConstraintViolationException.class}) ResponseEntity<Map<String,String>> validation(Exception exception){return ResponseEntity.badRequest().body(Map.of("error","请求参数无效"));} }
