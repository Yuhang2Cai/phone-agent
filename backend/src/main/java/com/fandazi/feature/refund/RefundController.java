// 售后 HTTP 接口：为已登录用户提供资格、确认和进度查询。
package com.fandazi.feature.refund;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 退款入口使用公开券编号，用户 ID 只能从认证上下文取得。 */
@RestController
@RequestMapping("/api/refunds")
public class RefundController {
    /** 用户确认前必须取得资格和动作专属凭证。 */
    public record PrepareRequest(@NotBlank String sessionId, @NotBlank String voucherId) {}
    /** 提交参数不得携带自报金额或用户 ID。 */
    public record SubmitRequest(@NotBlank String sessionId, @NotBlank String voucherId,
                                @NotBlank String reason, @NotBlank String confirmationGrant) {}
    private final RefundService refunds;

    /** 注入退款服务。 */
    public RefundController(RefundService refunds) { this.refunds = refunds; }

    /** 返回可申请券的金额和有效期，并签发短时确认凭证。 */
    @PostMapping("/prepare")
    public Map<String, Object> prepare(@AuthenticationPrincipal Long userId, @RequestBody PrepareRequest request) {
        return refunds.prepare(userId, request.sessionId(), request.voucherId());
    }

    /** 用户确认后创建待处理申请。 */
    @PostMapping
    public Map<String, Object> submit(@AuthenticationPrincipal Long userId, @RequestBody SubmitRequest request) {
        return refunds.request(userId, request.sessionId(), request.voucherId(), request.reason(), request.confirmationGrant());
    }

    /** 仅允许申请所属用户读取进度。 */
    @GetMapping("/{requestId}")
    public Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable String requestId) {
        return refunds.detail(userId, requestId);
    }

    /** 本人券详情重新进入进度时使用；没有申请返回404，响应不新增资格或假审核状态。 */
    @GetMapping("/by-voucher/{voucherId}")
    public org.springframework.http.ResponseEntity<Map<String, Object>> byVoucher(
            @AuthenticationPrincipal Long userId, @PathVariable String voucherId) {
        return org.springframework.http.ResponseEntity.of(refunds.detailByVoucher(userId, voucherId));
    }
}
