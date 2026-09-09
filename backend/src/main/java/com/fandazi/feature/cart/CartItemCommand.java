package com.fandazi.feature.cart;
import java.math.BigDecimal; import java.util.List;
public record CartItemCommand(Long dishId,int quantity,List<Long> optionIds,BigDecimal clientPrice) {}
