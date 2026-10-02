// 开发错误页验证入口：只对认证账号的下一次订单读取注入 503；不接触业务数据库。
package com.fandazi.development;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** production/prod 叠加时仍禁用，未显式开启时没有路由或故障状态；凭据由正常认证链校验。 */
@RestController
@RequestMapping("/api/development/order-failure")
@Profile("development & !prod & !production")
@ConditionalOnProperty(name = "task8.failures.enabled", havingValue = "true")
public class DevelopmentOrderFailure implements java.util.function.LongConsumer {
    /** 只存短期认证用户主键和截止时间，不保存令牌、密码、订单、金额或申请记录。 */
    private final Map<Long, Instant> deadlines = new ConcurrentHashMap<>();
    private final Duration lifetime;

    /** 开发入口默认只在一分钟内影响一次读取，过期自动失效。 */
    @Autowired
    public DevelopmentOrderFailure() { this(Duration.ofMinutes(1)); }

    /** 测试专用时限注入，不从 HTTP 接受任意故障时长或他人身份。 */
    DevelopmentOrderFailure(Duration lifetime) { this.lifetime = lifetime; }

    /** 认证账号显式启动一次错误页验证；响应不包含任何业务数据。 */
    @PostMapping
    public Map<String, Boolean> arm(@AuthenticationPrincipal Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now = Instant.now();
        // 清理过期验证状态，防止长时间本地开发积累无用记录。
        deadlines.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        deadlines.put(userId, now.plus(lifetime));
        return Map.of("armed", true);
    }

    /** 原子取走一次故障后返回 503；其他账号与再次请求继续执行真实领域查询。 */
    public void beforeList(Long userId) {
        Instant deadline = userId == null ? null : deadlines.remove(userId);
        if (deadline != null && deadline.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "开发验证：订单读取暂时不可用");
        }
    }

    /** 对接订单模块的可选JDK读取钩子；既有开发开关、本人范围及一次性503规则保持不变。 */
    @Override
    public void accept(long userId) {
        // 委托唯一故障判断，不能为接口适配复制状态或扩大到业务写入。
        beforeList(userId);
    }
}
