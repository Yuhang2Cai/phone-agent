// 支付模块：集中处理业务规则与状态变更。
package com.fandazi.feature.payment;
import com.fandazi.feature.DomainException; import com.fandazi.feature.order.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
// 初始化对象依赖与默认状态。
// 支付当前数据。
// 支付响应。
// 处理 `alreadyPaid` 相关逻辑。
// 支付内部。
@Service public class PaymentService { private final OrderService orders; public PaymentService(OrderService orders){this.orders=orders;} @Transactional public OrderEntity pay(Long userId,Long orderId){return payInternal(userId,orderId,"legacy-"+java.util.UUID.randomUUID());} @Transactional public OrderEntity pay(Long userId,Long orderId,String idempotencyKey){return payInternal(userId,orderId,idempotencyKey);} @Transactional public OrderResponse payResponse(Long userId,Long orderId,String idempotencyKey){return OrderResponse.from(payInternal(userId,orderId,idempotencyKey));} @Transactional(readOnly=true) public OrderEntity alreadyPaid(Long userId,Long orderId,String idempotencyKey){var order=orders.get(userId,orderId);return idempotencyKey!=null&&idempotencyKey.equals(order.getPaymentIdempotencyKey())?order:null;} private OrderEntity payInternal(Long userId,Long orderId,String idempotencyKey){if(idempotencyKey==null||idempotencyKey.isBlank())throw new DomainException("缺少支付幂等键");var order=orders.locked(userId,orderId);if(idempotencyKey.equals(order.getPaymentIdempotencyKey()))return order;if(order.getStatus()!=OrderStatus.PENDING_PAYMENT)throw new DomainException("订单已支付或无法支付");order.setPaymentIdempotencyKey(idempotencyKey);order.advance(OrderStatus.PAID);order.advance(OrderStatus.MERCHANT_ACCEPTED);return order;} }
