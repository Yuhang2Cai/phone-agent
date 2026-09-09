package com.fandazi.feature.order;

import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderInventoryRollbackTest {
    @Autowired private UserRepository users;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private AddressService addresses;
    @Autowired private CartService carts;
    @Autowired private OrderService orders;
    @Autowired private OrderRepository orderRepository;

    @Test
    void restoresEarlierStockWhenLaterDishCannotBeReserved() {
        UserEntity user = users.save(new UserEntity("rollback-user", "rollback@example.com", "hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "回滚餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity first = dishes.save(new DishEntity(
                merchant, "主食", "第一道", "", new BigDecimal("12.00"), 1, true, ""));
        DishEntity second = dishes.save(new DishEntity(
                merchant, "主食", "第二道", "", new BigDecimal("12.00"), 0, true, ""));
        var address = addresses.create(user.getId(), new AddressCommand("Rollback", "13800000000", "回滚路 1 号", true));
        var cart = carts.createDraft(user.getId(), merchant.getId());
        carts.replaceItems(user.getId(), cart.getId(), List.of(
                new CartItemCommand(first.getId(), 1, List.of(), null),
                new CartItemCommand(second.getId(), 1, List.of(), null)));

        assertThatThrownBy(() -> orders.create(user.getId(),
                new CreateOrderCommand(cart.getId(), address.getId(), "rollback-order")))
                .hasMessageContaining("库存");

        assertThat(dishes.findById(first.getId()).orElseThrow().getStock()).isEqualTo(1);
        assertThat(dishes.findById(second.getId()).orElseThrow().getStock()).isEqualTo(0);
        assertThat(orderRepository.count()).isZero();
    }
}
