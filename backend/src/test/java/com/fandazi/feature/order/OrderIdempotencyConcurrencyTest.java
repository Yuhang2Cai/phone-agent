// 订单模块：验证关键行为与边界条件。
package com.fandazi.feature.order;

import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.payment.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderIdempotencyConcurrencyTest {
    @Autowired private AddressService addresses;
    @Autowired private CartService carts;
    @Autowired private OrderService orders;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository users;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private TransactionTemplate transactions;
    @Autowired private PaymentService payments;

    @Test
    void concurrentCreatesWithTheSameKeyReplayOneOrderAndReserveStockOnce() throws Exception {
        UserEntity user = users.save(new UserEntity("order-race", "order-race@example.com", "hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "并发盖饭", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "限量盖饭", "", new BigDecimal("12.00"), 1, true, ""));
        AddressEntity address = addresses.create(user.getId(), new AddressCommand(
                "Race", "13800000000", "并发路 1 号", true));
        CartDraft cart = carts.createDraft(user.getId(), merchant.getId());
        carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = pool.submit(() -> createWhenReleased(start, user.getId(), cart.getId(), address.getId()));
            Future<Long> second = pool.submit(() -> createWhenReleased(start, user.getId(), cart.getId(), address.getId()));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(dishes.findById(dish.getId()).orElseThrow().getStock()).isZero();
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    private Long createWhenReleased(CountDownLatch start, Long userId, Long cartId, Long addressId) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return transactions.execute(status -> orders.create(
                userId, new CreateOrderCommand(cartId, addressId, "same-concurrent-key")).getId());
    }

    @Test
    void concurrentOrdersForTheLastDishReserveItOnlyOnce() throws Exception {
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "库存餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "最后一份", "", new BigDecimal("12.00"), 1, true, ""));
        UserEntity firstUser = users.save(new UserEntity("stock-first", "stock-first@example.com", "hash"));
        UserEntity secondUser = users.save(new UserEntity("stock-second", "stock-second@example.com", "hash"));
        CartDraft firstCart = cartFor(firstUser, merchant, dish);
        CartDraft secondCart = cartFor(secondUser, merchant, dish);
        AddressEntity firstAddress = addresses.create(firstUser.getId(), new AddressCommand("First", "13800000001", "一号", true));
        AddressEntity secondAddress = addresses.create(secondUser.getId(), new AddressCommand("Second", "13800000002", "二号", true));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> createIfAvailable(start, firstUser.getId(), firstCart.getId(), firstAddress.getId(), "stock-first"));
            Future<Boolean> second = pool.submit(() -> createIfAvailable(start, secondUser.getId(), secondCart.getId(), secondAddress.getId(), "stock-second"));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(dishes.findById(dish.getId()).orElseThrow().getStock()).isZero();
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsWithTheSameKeyReplayOneStateTransition() throws Exception {
        UserEntity user = users.save(new UserEntity("payment-race", "payment-race@example.com", "hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "支付餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "支付盖饭", "", new BigDecimal("12.00"), 2, true, ""));
        CartDraft cart = cartFor(user, merchant, dish);
        AddressEntity address = addresses.create(user.getId(), new AddressCommand("Pay", "13800000000", "支付路", true));
        OrderEntity order = orders.create(user.getId(), new CreateOrderCommand(cart.getId(), address.getId(), "payment-order"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OrderStatus> first = pool.submit(() -> payWhenReleased(start, user.getId(), order.getId()));
            Future<OrderStatus> second = pool.submit(() -> payWhenReleased(start, user.getId(), order.getId()));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(OrderStatus.MERCHANT_ACCEPTED);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(OrderStatus.MERCHANT_ACCEPTED);
        } finally {
            pool.shutdownNow();
        }

        assertThat(orders.get(user.getId(), order.getId()).getTimeline())
                .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.MERCHANT_ACCEPTED);
    }

    private CartDraft cartFor(UserEntity user, MerchantEntity merchant, DishEntity dish) {
        CartDraft cart = carts.createDraft(user.getId(), merchant.getId());
        carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
        return cart;
    }

    private Boolean createIfAvailable(CountDownLatch start, Long userId, Long cartId, Long addressId, String key) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            transactions.execute(status -> {
                orders.create(userId, new CreateOrderCommand(cartId, addressId, key));
                return true;
            });
            return true;
        } catch (DomainException expectedStockFailure) {
            return false;
        }
    }

    private OrderStatus payWhenReleased(CountDownLatch start, Long userId, Long orderId) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return transactions.execute(status -> payments.pay(userId, orderId, "same-payment-key").getStatus());
    }
}
