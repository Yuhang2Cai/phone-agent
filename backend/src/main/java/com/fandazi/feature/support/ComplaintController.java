// 投诉 HTTP 接口：明确区分确认前预览与确认后的写入。
package com.fandazi.feature.support;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 只从认证态获取用户，订单号可省略但不可伪造归属。 */
@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {
    /** 预览参数含会话和可选订单。 */
    public record PrepareRequest(@NotBlank String sessionId, String orderId) {}
    /** 提交时需要同一资源的动作专属确认凭证。 */
    public record SubmitRequest(@NotBlank String sessionId, String orderId, @NotBlank String subject,
                                @NotBlank String description, @NotBlank String confirmationGrant) {}
    private final ComplaintService complaints;

    /** 注入投诉服务。 */
    public ComplaintController(ComplaintService complaints) { this.complaints = complaints; }

    /** 仅返回反馈类型目录；不读用户订单，不提供改变工单状态的开发捷径。 */
    @GetMapping("/types")
    public java.util.List<Map<String, String>> types() { return ComplaintService.TYPES; }

    /** 校验关联订单后签发短时确认凭证。 */
    @PostMapping("/prepare")
    public Map<String, Object> prepare(@AuthenticationPrincipal Long userId, @RequestBody PrepareRequest request) {
        return complaints.prepare(userId, request.sessionId(), request.orderId());
    }

    /** 用户显式确认后记录投诉工单。 */
    @PostMapping
    public Map<String, Object> submit(@AuthenticationPrincipal Long userId, @RequestBody SubmitRequest request) {
        return complaints.submit(userId, request.sessionId(), request.orderId(), request.subject(), request.description(), request.confirmationGrant());
    }
}
