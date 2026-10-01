// 团购购买模块：为 Android 提供报价接口，报价中的金额和套餐字段均来自后端。
package com.fandazi.feature.purchase;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 报价接口，不直接创建订单。 */
@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {
    public record QuoteRequest(@NotBlank String dealId, @Min(1) int quantity, String sessionId) {}
    private final PurchaseQuoteService quotes;

    /** 注入报价服务。 */
    public PurchaseController(PurchaseQuoteService quotes) { this.quotes = quotes; }

    /** 创建五分钟有效报价并返回一次性确认凭证。 */
    @PostMapping("/quotes")
    public java.util.Map<String, Object> quote(@AuthenticationPrincipal Long userId, @RequestBody QuoteRequest request) {
        return quotes.create(userId, request.sessionId(), request.dealId(), request.quantity());
    }
}
