// 秒杀本人HTTP入口：由全局认证保护，不接受客户端提供买家ID，不缓存资格或订单回执。
package com.fandazi.feature.seckill;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 仅提供只读核查，不暴露发布、修改、停止活动或后台扫描控制入口。 */
@RestController
public class SeckillPersonalController {
    private final SeckillPersonalService service;
    /** 注入本人只读查询服务。 */
    public SeckillPersonalController(SeckillPersonalService service){this.service=service;}
    /** 读取当前认证用户的活动资格，客户端不得按别人的资格解锁按钮。 */
    @GetMapping("/api/seckill-activities/{activityId}/eligibility")
    public ResponseEntity<Map<String,Object>> eligibility(@AuthenticationPrincipal Long user,@PathVariable String activityId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.eligibility(user,activityId));
    }
    /** 查询本人原请求；即使活动结束或订单关闭，仍可恢复原成功事实。 */
    @GetMapping("/api/seckill-purchases/requests/{requestId}")
    public ResponseEntity<Map<String,Object>> original(@AuthenticationPrincipal Long user,@PathVariable String requestId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.originalRequest(user,requestId));
    }
}
