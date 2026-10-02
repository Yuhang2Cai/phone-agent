// 团购券模块：提供用户侧团购券查询和短时二维码展示接口。
package com.fandazi.feature.voucher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 团购券用户接口；所有读写都要求登录身份。 */
@RestController
@RequestMapping("/api/vouchers")
public class VoucherController {
    public record RedemptionRequest(@NotBlank String token, @NotBlank String storeId) {}
    private final VoucherService service;

    /** 注入团购券业务服务。 */
    public VoucherController(VoucherService service) { this.service = service; }

    /** 查询当前用户团购券。 */
    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Long userId) { return service.list(userId); }

    /** 查询当前用户的券详情。 */
    @GetMapping("/{publicId}")
    public Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable String publicId) {
        // 页面读取需要所属订单信息；Agent 只读工具继续复用既有不带令牌的安全摘要。
        return service.detailForPage(userId, publicId);
    }

    /** 按需生成短时二维码展示令牌。 */
    @PostMapping("/{publicId}/display-token")
    public Map<String, Object> displayToken(@AuthenticationPrincipal Long userId, @PathVariable String publicId) { return service.displayToken(userId, publicId); }

    /** 门店演示核销接口；上线前应替换为门店员工身份和扫码设备权限。 */
    @PostMapping("/demo-redemption")
    public Map<String, Object> redeem(@AuthenticationPrincipal Long userId, @Valid @RequestBody RedemptionRequest request) {
        if (userId == null) throw new org.springframework.security.access.AccessDeniedException("需要登录");
        return service.redeem(request.token(), request.storeId());
    }
}
