// 配送模块：集中处理业务规则与状态变更。
package com.fandazi.feature.delivery;

import com.fandazi.feature.order.OrderEntity;
import com.fandazi.feature.order.OrderService;
import com.fandazi.feature.order.OrderStatus;
import com.fandazi.feature.DomainException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {
    private static final Set<OrderStatus> DELIVERY_STATES = Set.of(
            OrderStatus.PREPARING, OrderStatus.RIDER_PICKED_UP,
            OrderStatus.DELIVERING, OrderStatus.DELIVERED);

    private final OrderService orders;

    public DeliveryService(OrderService orders) {
        this.orders = orders;
    }

    @Transactional
    public OrderEntity advance(Long userId, Long orderId, OrderStatus next) {
        if (!DELIVERY_STATES.contains(next)) {
            throw new DomainException("配送状态无效");
        }
        var current = orders.get(userId, orderId).getStatus();
        return orders.transition(userId, orderId, current, next);
    }
}
