package com.fandazi.feature.order;
import jakarta.validation.constraints.NotBlank; import jakarta.validation.constraints.NotNull;
public record CreateOrderCommand(@NotNull(message="购物车不能为空") Long cartDraftId,
                                 @NotNull(message="地址不能为空") Long addressId,
                                 @NotBlank(message="幂等键不能为空") String idempotencyKey) {}
