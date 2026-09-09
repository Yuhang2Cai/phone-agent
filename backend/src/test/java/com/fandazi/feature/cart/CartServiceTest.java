package com.fandazi.feature.cart;

import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.DishOptionEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
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
class CartServiceTest {
    @Autowired private CartService carts;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private DishOptionRepository options;
    @Autowired private UserRepository users;

    private UserEntity alice;
    private MerchantEntity merchant;

    @BeforeEach
    void setUp() {
        alice = users.save(new UserEntity("alice", "alice@example.com", "hash"));
        merchant = merchants.save(new MerchantEntity("好吃面馆", "面食", new BigDecimal("20.00"), new BigDecimal("3.00"), 30, true));
    }

    @Test
    void recalculatesPriceFromCurrentDishDataInsteadOfClientPrice() {
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "牛肉面", "", new BigDecimal("12.00"), 10, true, "/images/noodle.png"));
        CartDraft draft = carts.createDraft(alice.getId(), merchant.getId());

        carts.replaceItems(alice.getId(), draft.getId(), List.of(new CartItemCommand(dish.getId(), 2, List.of(), new BigDecimal("0.01"))));
        CartQuote quote = carts.validate(alice.getId(), draft.getId());

        assertThat(quote.subtotal()).isEqualByComparingTo("24.00");
        assertThat(quote.deliveryFee()).isEqualByComparingTo("3.00");
        assertThat(quote.total()).isEqualByComparingTo("27.00");
    }

    @Test
    void persistsValidOptionsAndRecalculatesTheirPriceDelta() {
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "牛肉面", "", new BigDecimal("12.00"), 10, true, "/images/noodle.png"));
        DishOptionEntity spicy = options.save(new DishOptionEntity(dish, "辣度", "中辣", new BigDecimal("2.00")));
        CartDraft draft = carts.createDraft(alice.getId(), merchant.getId());

        carts.replaceItems(alice.getId(), draft.getId(), List.of(new CartItemCommand(dish.getId(), 2, List.of(spicy.getId()), null)));
        CartQuote quote = carts.validate(alice.getId(), draft.getId());

        assertThat(quote.subtotal()).isEqualByComparingTo("28.00");
        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.optionIds()).containsExactly(spicy.getId()));
    }

    @Test
    void rejectsOptionFromAnotherDish() {
        DishEntity first = dishes.save(new DishEntity(merchant, "主食", "牛肉面", "", new BigDecimal("12.00"), 10, true, ""));
        DishEntity second = dishes.save(new DishEntity(merchant, "主食", "炸酱面", "", new BigDecimal("12.00"), 10, true, ""));
        DishOptionEntity option = options.save(new DishOptionEntity(second, "辣度", "中辣", new BigDecimal("2.00")));
        CartDraft draft = carts.createDraft(alice.getId(), merchant.getId());
        carts.replaceItems(alice.getId(), draft.getId(), List.of(new CartItemCommand(first.getId(), 1, List.of(option.getId()), null)));

        assertThatThrownBy(() -> carts.validate(alice.getId(), draft.getId())).hasMessageContaining("规格");
    }

    @Test
    void rejectsUnavailableDishAndOrderBelowMerchantMinimum() {
        DishEntity unavailable = dishes.save(new DishEntity(merchant, "主食", "售罄面", "", new BigDecimal("30.00"), 0, true, ""));
        CartDraft unavailableDraft = carts.createDraft(alice.getId(), merchant.getId());
        carts.replaceItems(alice.getId(), unavailableDraft.getId(), List.of(new CartItemCommand(unavailable.getId(), 1, List.of(), null)));
        assertThatThrownBy(() -> carts.validate(alice.getId(), unavailableDraft.getId()))
                .hasMessageContaining("库存");

        DishEntity cheap = dishes.save(new DishEntity(merchant, "主食", "小菜", "", new BigDecimal("10.00"), 5, true, ""));
        CartDraft cheapDraft = carts.createDraft(alice.getId(), merchant.getId());
        carts.replaceItems(alice.getId(), cheapDraft.getId(), List.of(new CartItemCommand(cheap.getId(), 1, List.of(), null)));
        assertThatThrownBy(() -> carts.validate(alice.getId(), cheapDraft.getId()))
                .hasMessageContaining("起送价");
    }

    @Test
    void doesNotExposeAnotherUsersCart() {
        UserEntity bob = users.save(new UserEntity("bob", "bob@example.com", "hash"));
        CartDraft draft = carts.createDraft(alice.getId(), merchant.getId());

        assertThatThrownBy(() -> carts.validate(bob.getId(), draft.getId()))
                .hasMessageContaining("购物车");
    }
}
