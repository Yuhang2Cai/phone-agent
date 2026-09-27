// 购物车模块：定义输入参数及其约束。
package com.fandazi.feature.cart;
import jakarta.validation.constraints.NotNull; import jakarta.validation.constraints.Positive;
import java.math.BigDecimal; import java.util.List;
public record CartItemCommand(@NotNull(message="菜品不能为空") Long dishId,
                              @Positive(message="数量必须为正数") int quantity,
                              List<Long> optionIds, BigDecimal clientPrice) {}
