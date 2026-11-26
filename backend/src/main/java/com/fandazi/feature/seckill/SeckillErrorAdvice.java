// 秒杀HTTP错误映射：保留既有code/error信封，补reasonCode并禁止缓存动态资格拒绝。
package com.fandazi.feature.seckill;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
/** 只匹配秒杀专用异常，普通团购继续使用原DomainExceptionHandler。 */
@RestControllerAdvice
// Spring按Advice顺序选首个匹配项，必须先于通用DomainException出口识别本期专用子类。
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class SeckillErrorAdvice {
    /** 将已知业务原因映射为4xx，依赖故障继续按5xx处理，不能伪装成售罄。 */
    @ExceptionHandler(SeckillRejectedException.class)
    public ResponseEntity<Map<String,String>> rejected(SeckillRejectedException error){
        HttpStatus status=switch(error.getCode()){
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "AMOUNT_EXCEEDED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code",error.getCode(),"reasonCode",error.getCode(),"error",error.getMessage()));
    }
}
