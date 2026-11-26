// 金币读取适配：与活动服务共用账本和身份，不提供直接修改余额接口。
package com.fandazi.feature.activity;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 余额与流水只读，所有变更必须由任务奖励或可信退款事务产生。 */
@RestController
@RequestMapping("/api/coins")
public class CoinController {
    private final ActivityService service;
    private final ActivityAdmission admission;
    /** 注入同一事务服务和按用户计数的准入组件。 */
    public CoinController(ActivityService service,ActivityAdmission admission){this.service=service;this.admission=admission;}
    /** 返回本人余额，不接受请求参数中的用户标识。 */
    @GetMapping("/account")
    public Map<String,Object> account(@AuthenticationPrincipal Long user) {
        long owner=ActivityTaskController.owner(user);admission.check("user:"+owner,false);return service.account(owner);
    }
    /** 游标为本人上一页流水公开 ID，大小限定1至100。 */
    @GetMapping("/transactions")
    public Map<String,Object> transactions(@AuthenticationPrincipal Long user,@RequestParam(required=false) String cursor,
                                           @RequestParam(defaultValue="20") int limit) {
        long owner=ActivityTaskController.owner(user);admission.check("user:"+owner,false);return service.transactions(owner,cursor,limit);
    }
}
