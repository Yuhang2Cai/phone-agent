// 活动 HTTP 适配：认证主体提供用户身份，路径与请求体只提供公开任务引用和预期版本。
package com.fandazi.feature.activity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 仅发布读取、领取和手动领奖；没有客户端进度上报或退款成功写入口。 */
@RestController
@RequestMapping("/api/activities")
public class ActivityTaskController {
    private final ActivityService service;
    private final ActivityAdmission admission;
    /** 注入权威服务和有界准入，事务只在服务内部开启。 */
    public ActivityTaskController(ActivityService service,ActivityAdmission admission){this.service=service;this.admission=admission;}
    /** 游客获得公开定义，私有实例仅在有效认证时返回。 */
    @GetMapping("/tasks")
    public Map<String,Object> tasks(@AuthenticationPrincipal Long user,HttpServletRequest request,@RequestParam(required=false) String requestId) {
        admission.check(user==null?"guest:"+request.getRemoteAddr():"user:"+user,false);return service.tasks(user,requestId);
    }
    /** 领取时要求用户确认的规则版本和一次生成的请求键，额外权威字段在进入事务前拒绝。 */
    @PostMapping("/tasks/{id}/accept")
    public Map<String,Object> accept(@AuthenticationPrincipal Long user,@PathVariable String id,
                                     @RequestHeader("Idempotency-Key") String request,@RequestBody JsonNode body) {
        long owner=owner(user);int version=ActivityInput.version(body);admission.check("user:"+owner,true);
        return service.accept(owner,id,version,request);
    }
    /** 详情可用于未知写回读，但不会自动领取或自动发币。 */
    @GetMapping("/task-instances/{id}")
    public Map<String,Object> detail(@AuthenticationPrincipal Long user,@PathVariable String id) {
        long owner=owner(user);admission.check("user:"+owner,false);return service.detail(owner,id);
    }
    /** 手动领奖只接收空对象和幂等键，奖励金额始终取领取快照。 */
    @PostMapping("/task-instances/{id}/claim")
    public Map<String,Object> claim(@AuthenticationPrincipal Long user,@PathVariable String id,
                                    @RequestHeader("Idempotency-Key") String request,@RequestBody JsonNode body) {
        long owner=owner(user);ActivityInput.empty(body);admission.check("user:"+owner,true);return service.claim(owner,id,request);
    }
    /** 安全链之外保留明确的领域入口保护，防止其他内部调用把 null 转成游客写入。 */
    static long owner(Long user){if(user==null||user<=0)throw new ActivityException(401,"LOGIN_REQUIRED");return user;}
}
