// 订单服务：在事务内处理库存预留、幂等和订单创建。
package com.fandazi.feature.order;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.cart.CartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final UserRepository users;
    private final AddressService addresses;
    private final CartService carts;

// 初始化对象依赖与默认状态。
    public OrderService(OrderRepository orders, UserRepository users, AddressService addresses, CartService carts) {
        this.orders = orders;
        this.users = users;
        this.addresses = addresses;
        this.carts = carts;
    }

// 创建当前数据。
    @Transactional
    public OrderEntity create(Long userId, CreateOrderCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new DomainException("缺少幂等键");
        }

        // 在预留库存前锁定用户记录，使重复创建请求串行执行。
        var user = users.findByIdForUpdate(userId).orElseThrow(() -> new DomainException("用户不存在"));
        String fingerprint = command.cartDraftId() + ":" + command.addressId();
        var existing = orders.findByUserIdAndIdempotencyKey(userId, command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
                throw new OrderConflictException("幂等键对应不同请求");
            }
            return existing.get();
        }

        var address = addresses.owned(userId, command.addressId());
        var merchantName = carts.owned(userId, command.cartDraftId()).getMerchant().getName();
        var quote = carts.reserveAndQuote(userId, command.cartDraftId());
        var order = new OrderEntity(user, command.idempotencyKey(), fingerprint, merchantName,
                address.getRecipient(), address.getPhone(), address.getDetail(),
                quote.subtotal(), quote.deliveryFee(), quote.discount(), quote.total());
        for (var line : quote.lines()) {
            order.addItem(new OrderItemEntity(order, line.dishId(), line.dishName(), line.quantity(),
                    line.optionIds(), line.optionSummary(), line.unitPrice()));
        }
        return orders.save(order);
    }

// 创建响应。
    @Transactional
    public OrderResponse createResponse(Long userId, CreateOrderCommand command) {
        return OrderResponse.from(create(userId, command));
    }

// 查询by、幂等、键。
    @Transactional(readOnly = true)
    public java.util.Optional<OrderEntity> findByIdempotencyKey(Long userId, String key) {
        return orders.findByUserIdAndIdempotencyKey(userId, key);
    }

// 获取当前数据。
    @Transactional(readOnly = true)
    public OrderEntity get(Long userId, Long id) {
        var order = orders.findByIdAndUserId(id, userId).orElseThrow(() -> new DomainException("订单不存在"));
        order.getTimeline();
        order.getItems().forEach(item -> item.getOptionIds());
        return order;
    }

// 获取响应。
    @Transactional(readOnly = true)
    public OrderResponse getResponse(Long userId, Long id) {
        return OrderResponse.from(orders.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new DomainException("订单不存在")));
    }

// 列出responses。
    @Transactional(readOnly = true)
    public java.util.List<OrderResponse> listResponses(Long userId) {
        return orders.findByUserIdOrderByIdDesc(userId).stream()
                .peek(order -> {
                    order.getTimeline();
                    order.getItems().forEach(item -> item.getOptionIds());
                })
                .map(OrderResponse::from)
                .toList();
    }

// 处理 `locked` 相关逻辑。
    public OrderEntity locked(Long userId, Long id) {
        return orders.findByIdAndUserIdForUpdate(id, userId).orElseThrow(() -> new DomainException("订单不存在"));
    }

// 处理 `transition` 相关逻辑。
    @Transactional
    public OrderEntity transition(Long userId, Long id, OrderStatus expected, OrderStatus next) {
        var order = locked(userId, id);
        if (order.getStatus() != expected) {
            throw new DomainException("订单状态不可逆或跳跃");
        }
        order.advance(next);
        return order;
    }
}
