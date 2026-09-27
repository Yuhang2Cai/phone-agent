// 购物车模块：提供接口并将请求交给业务层。
package com.fandazi.feature.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart/drafts")
public class CartController {
    private final CartService carts;

// 初始化对象依赖与默认状态。
    public CartController(CartService carts) {
        this.carts = carts;
    }

    public record CreateDraftRequest(@NotNull @Positive Long merchantId) {}

// 创建当前数据。
    @PostMapping
    public CartDraftResponse create(@AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody CreateDraftRequest request) {
        return CartDraftResponse.from(carts.createDraft(userId, request.merchantId()));
    }

// 处理 `items` 相关逻辑。
    @PutMapping("/{id}/items")
    public CartDraftResponse items(@AuthenticationPrincipal Long userId,
                                   @PathVariable Long id,
                                   @Valid @RequestBody List<@Valid CartItemCommand> items) {
        return CartDraftResponse.from(carts.replaceItems(userId, id, items));
    }

// 校验当前数据。
    @PostMapping("/{id}/validate")
    public CartQuote validate(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return carts.validate(userId, id);
    }
}
