package com.fandazi.feature.order;

import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.delivery.DeliveryService;
import com.fandazi.feature.payment.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
class OrderServiceTest {
    @Autowired private AddressService addresses;
    @Autowired private CartService carts;
    @Autowired private OrderService orders;
    @Autowired private PaymentService payments;
    @Autowired private DeliveryService delivery;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private UserRepository users;

    private UserEntity alice;
    private MerchantEntity merchant;
    private DishEntity dish;

    @BeforeEach
    void setUp() {
        alice = users.save(new UserEntity("alice", "alice@example.com", "hash"));
        merchant = merchants.save(new MerchantEntity("饭搭子盖饭", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        dish = dishes.save(new DishEntity(merchant, "主食", "招牌盖饭", "", new BigDecimal("12.00"), 10, true, ""));
    }

    @Test
    void keepsOnlyOneDefaultAddressAndRejectsCrossUserAddressAccess() {
        AddressEntity first = addresses.create(alice.getId(), new AddressCommand("Alice", "13800000000", "A 路 1 号", true));
        AddressEntity second = addresses.create(alice.getId(), new AddressCommand("Alice", "13800000000", "A 路 2 号", true));
        UserEntity bob = users.save(new UserEntity("bob", "bob@example.com", "hash"));

        assertThat(addresses.list(alice.getId())).filteredOn(AddressEntity::isDefaultAddress).hasSize(1)
                .first().extracting(AddressEntity::getId).isEqualTo(second.getId());
        assertThatThrownBy(() -> addresses.setDefault(bob.getId(), first.getId()))
                .hasMessageContaining("地址");
    }

    @Test
    void createsOneOrderForRepeatedIdempotencyKeyAndSnapshotsServerQuote() {
        AddressEntity address = addresses.create(alice.getId(), new AddressCommand("Alice", "13800000000", "A 路 1 号", true));
        CartDraft cart = carts.createDraft(alice.getId(), merchant.getId());
        carts.replaceItems(alice.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), new BigDecimal("0.01"))));

        OrderEntity first = orders.create(alice.getId(), new CreateOrderCommand(cart.getId(), address.getId(), "order-001"));
        OrderEntity replay = orders.create(alice.getId(), new CreateOrderCommand(cart.getId(), address.getId(), "order-001"));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(first.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(first.getTotal()).isEqualByComparingTo("14.00");
        assertThat(first.getAddressDetail()).isEqualTo("A 路 1 号");
        assertThat(first.getItems()).singleElement().satisfies(item -> assertThat(item.getUnitPrice()).isEqualByComparingTo("12.00"));
    }

    @Test
    void rejectsDuplicatePaymentAndOnlyAllowsForwardDeliveryTransitions() {
        AddressEntity address = addresses.create(alice.getId(), new AddressCommand("Alice", "13800000000", "A 路 1 号", true));
        CartDraft cart = carts.createDraft(alice.getId(), merchant.getId());
        carts.replaceItems(alice.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
        OrderEntity order = orders.create(alice.getId(), new CreateOrderCommand(cart.getId(), address.getId(), "order-002"));

        payments.pay(alice.getId(), order.getId());
        assertThatThrownBy(() -> payments.pay(alice.getId(), order.getId())).hasMessageContaining("支付");
        assertThatThrownBy(() -> delivery.advance(alice.getId(), order.getId(), OrderStatus.DELIVERING)).hasMessageContaining("状态");

        delivery.advance(alice.getId(), order.getId(), OrderStatus.PREPARING);
        delivery.advance(alice.getId(), order.getId(), OrderStatus.RIDER_PICKED_UP);
        delivery.advance(alice.getId(), order.getId(), OrderStatus.DELIVERING);
        delivery.advance(alice.getId(), order.getId(), OrderStatus.DELIVERED);
        assertThat(orders.get(alice.getId(), order.getId()).getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThatThrownBy(() -> delivery.advance(alice.getId(), order.getId(), OrderStatus.PREPARING)).hasMessageContaining("状态");
    }
}
