package com.fandazi.feature.delivery;
import com.fandazi.feature.order.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service public class DeliveryService { private final OrderService orders; public DeliveryService(OrderService orders){this.orders=orders;} @Transactional public OrderEntity advance(Long userId,Long orderId,OrderStatus next){var current=orders.get(userId,orderId).getStatus();return orders.transition(userId,orderId,current,next);} }
