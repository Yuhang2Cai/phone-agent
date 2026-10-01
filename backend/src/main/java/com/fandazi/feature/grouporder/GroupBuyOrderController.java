// 团购订单模块：提供创建待支付订单和模拟支付接口。
package com.fandazi.feature.grouporder;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 团购订单接口只接受公开 ID、确认凭证和幂等键，不接受客户端金额。 */
@RestController
@RequestMapping("/api/group-orders")
public class GroupBuyOrderController {
    public record CreateRequest(@NotBlank String dealId, @Min(1) int quantity, @NotBlank String confirmationGrant, @NotBlank String sessionId, @NotBlank String idempotencyKey) {}
    public record PayRequest(@NotBlank String idempotencyKey) {}
    private final GroupBuyOrderService orders;

    /** 注入团购订单服务。 */
    public GroupBuyOrderController(GroupBuyOrderService orders) { this.orders = orders; }

    /** 消费一次性确认凭证并创建待支付订单。 */
    @PostMapping
    public java.util.Map<String, Object> create(@AuthenticationPrincipal Long userId, @RequestBody CreateRequest request) {
        return orders.create(userId, request.sessionId(), request.dealId(), request.quantity(), request.confirmationGrant(), request.idempotencyKey());
    }

    /** 执行演示支付，不连接真实支付渠道。 */
    @PostMapping("/{orderId}/mock-pay")
    public java.util.Map<String, Object> pay(@AuthenticationPrincipal Long userId, @PathVariable String orderId, @RequestBody PayRequest request) {
        return orders.pay(userId, orderId, request.idempotencyKey());
    }

    /** 返回当前用户的团购订单列表。 */
    @GetMapping
    public java.util.List<java.util.Map<String, Object>> list(@AuthenticationPrincipal Long userId) {
        return orders.list(userId);
    }

    /** 返回当前用户指定团购订单详情和券摘要。 */
    @GetMapping("/{orderId}")
    public java.util.Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable String orderId) {
        return orders.detail(userId, orderId);
    }
}
