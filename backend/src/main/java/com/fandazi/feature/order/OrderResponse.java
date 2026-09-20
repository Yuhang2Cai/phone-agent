package com.fandazi.feature.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
        Long id, OrderStatus status, String recipient, String phone, String detail,
        BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal discount, BigDecimal total,
        List<Item> items, List<OrderStatus> timeline, String merchantName, LocalDateTime createdAt
) {
    public record Item(Long dishId, String dishName, int quantity, List<Long> optionIds,
                       String optionSummary, BigDecimal unitPrice) {}

    public static OrderResponse from(OrderEntity order) {
        return new OrderResponse(
                order.getId(), order.getStatus(), order.getAddressRecipient(), order.getAddressPhone(),
                order.getAddressDetail(), order.getSubtotal(), order.getDeliveryFee(), order.getDiscount(),
                order.getTotal(), order.getItems().stream().map(item -> new Item(
                        item.getDishId(), item.getDishName(), item.getQuantity(), item.getOptionIds(),
                        item.getOptionSummary(), item.getUnitPrice())).toList(), order.getTimeline(),
                order.getMerchantName(), order.getCreatedAt());
    }
}
