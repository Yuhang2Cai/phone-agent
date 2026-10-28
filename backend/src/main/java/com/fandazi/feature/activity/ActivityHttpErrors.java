// 活动专用错误出口：稳定原因码用于页面白名单，数据库异常不能伪装为200空任务。
package com.fandazi.feature.activity;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 仅影响活动及金币控制器，不改变其他模块的历史错误信封。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={ActivityTaskController.class,CoinController.class})
public class ActivityHttpErrors {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ActivityHttpErrors.class);
    /** 保留领域状态，429附带重试等待提示，客户端仍不得自动重放写请求。 */
    @ExceptionHandler(ActivityException.class)
    public ResponseEntity<Map<String,String>> activity(ActivityException error) {
        var response=ResponseEntity.status(error.status());if(error.status()==429)response.header("Retry-After","60");
        return response.body(Map.of("reasonCode",error.reasonCode()));
    }
    /** 缺头、损坏JSON及参数类型错误统一归入可修正请求错误，不暴露解析器正文。 */
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception error){return ResponseEntity.badRequest().body(Map.of("reasonCode","INVALID_REQUEST"));}
    /** 数据库/配置故障返回503并记录异常类型；响应不含SQL、连接信息或用户数据。 */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,IllegalArgumentException.class})
    public ResponseEntity<Map<String,String>> unavailable(Exception error) {
        LOG.error("活动依赖或配置异常，类型={}",error.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("reasonCode","SERVICE_UNAVAILABLE"));
    }
}
