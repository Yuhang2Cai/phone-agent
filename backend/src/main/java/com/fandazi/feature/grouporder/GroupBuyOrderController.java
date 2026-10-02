// 团购订单模块：提供创建待支付订单和模拟支付接口。
package com.fandazi.feature.grouporder;

import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 团购订单接口只接受公开 ID、确认凭证和幂等键，不接受客户端金额。 */
@RestController
@RequestMapping("/api/group-orders")
public class GroupBuyOrderController {
    /** quoteId 必填并绑定已展示快照；历史套餐/数量字段不能再作为跳过报价的购买入口。 */
    public record CreateRequest(@NotBlank String quoteId, String dealId, int quantity, @NotBlank String confirmationGrant, @NotBlank String sessionId, @NotBlank String idempotencyKey) {}
    public record PayRequest(@NotBlank String idempotencyKey) {}
    private final GroupBuyOrderService orders;
    private final com.fandazi.feature.purchase.PurchaseQuoteService quotes;
    /** 可选开发故障组件默认不存在；生产路径只调用真实领域服务。 */
    private final org.springframework.beans.factory.ObjectProvider<com.fandazi.development.DevelopmentOrderFailure> failures;

    /** 注入团购订单服务。 */
    public GroupBuyOrderController(GroupBuyOrderService orders, com.fandazi.feature.purchase.PurchaseQuoteService quotes,
            org.springframework.beans.factory.ObjectProvider<com.fandazi.development.DevelopmentOrderFailure> failures) {
        this.orders = orders; this.quotes = quotes; this.failures = failures;
    }

    /** 消费一次性确认凭证并创建待支付订单。 */
    @PostMapping
    public java.util.Map<String, Object> create(@AuthenticationPrincipal Long userId, @jakarta.validation.Valid @RequestBody CreateRequest request) {
        // 页面提交已展示的报价身份，写事务复核用户、会话、数量、金额、期限和版本。
        // 公开 HTTP 不退回历史字段写入，缺报价身份由 @Valid 在进入领域事务前拒绝。
        return orders.createFromQuote(userId, request.sessionId(), quotes.owned(userId, request.quoteId()), request.confirmationGrant(), request.idempotencyKey());
    }

    /** 执行演示支付，不连接真实支付渠道。 */
    @PostMapping("/{orderId}/mock-pay")
    public java.util.Map<String, Object> pay(@AuthenticationPrincipal Long userId, @PathVariable String orderId, @RequestBody PayRequest request) {
        return orders.pay(userId, orderId, request.idempotencyKey());
    }

    /** 返回当前用户的团购订单列表。 */
    @GetMapping
    public java.util.List<java.util.Map<String, Object>> list(@AuthenticationPrincipal Long userId) {
        // 只有 development 显式开关且本人已启动验证时才触发一次 503；恢复重试仍读取原订单数据。
        failures.ifAvailable(failure -> failure.beforeList(userId));
        return orders.list(userId);
    }

    /** 返回当前用户指定团购订单详情和券摘要。 */
    @GetMapping("/{orderId}")
    public java.util.Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable String orderId) {
        return orders.detail(userId, orderId);
    }
}
