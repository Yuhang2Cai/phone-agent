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

    public OrderService(OrderRepository orders, UserRepository users, AddressService addresses, CartService carts) {
        this.orders = orders;
        this.users = users;
        this.addresses = addresses;
        this.carts = carts;
    }

    @Transactional
    public OrderEntity create(Long userId, CreateOrderCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new DomainException("缺少幂等键");
        }

        // A user-row lock serializes duplicate creates before inventory is reserved.
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
        var quote = carts.reserveAndQuote(userId, command.cartDraftId());
        var order = new OrderEntity(user, command.idempotencyKey(), fingerprint,
                address.getRecipient(), address.getPhone(), address.getDetail(),
                quote.subtotal(), quote.deliveryFee(), quote.total());
        for (var line : quote.lines()) {
            order.addItem(new OrderItemEntity(order, line.dishId(), line.dishName(), line.quantity(),
                    line.optionIds(), line.optionSummary(), line.unitPrice()));
        }
        return orders.save(order);
    }

    @Transactional(readOnly = true)
    public OrderEntity get(Long userId, Long id) {
        var order = orders.findByIdAndUserId(id, userId).orElseThrow(() -> new DomainException("订单不存在"));
        order.getTimeline();
        order.getItems().forEach(item -> item.getOptionIds());
        return order;
    }

    public OrderEntity locked(Long userId, Long id) {
        return orders.findByIdAndUserIdForUpdate(userId, id).orElseThrow(() -> new DomainException("订单不存在"));
    }

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
