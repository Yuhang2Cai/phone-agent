package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressRepository;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.order.OrderStatus;
import com.fandazi.feature.order.OrderRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "seed.enabled=true",
        "seed.path=../data/seed"
})
class SeedDataLoaderTest {

    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private DishOptionRepository options;
    @Autowired private AddressRepository addresses;
    @Autowired private OrderRepository orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeedDataLoader loader;

    @Test
    void loadsDeterministicCatalogAddressAndPaidOrderFixtures() {
        assertThat(merchants.count()).isEqualTo(12);
        assertThat(dishes.count()).isBetween(100L, 140L);
        assertThat(options.findAll())
                .anySatisfy(option -> assertThat(option.getGroupName()).isEqualTo("辣度"));
        assertThat(dishes.findAll())
                .filteredOn(DishEntity::isListed)
                .hasSizeLessThan((int) dishes.count());
        assertThat(dishes.findAll())
                .anySatisfy(dish -> assertThat(dish.getPrice()).isEqualByComparingTo(new BigDecimal("18.80")));
        assertThat(addresses.findAll())
                .filteredOn(AddressEntity::isDefaultAddress)
                .singleElement()
                .extracting(AddressEntity::getDetail)
                .isEqualTo("演示市春风路 88 号 3 栋 1201");
        assertThat(addresses.findAll())
                .extracting(AddressEntity::getDetail)
                .contains("已失效地址：演示市旧站路 9 号");
        assertThat(orders.findAll()).anySatisfy(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getItems()).singleElement().extracting(item -> item.getDishName()).isEqualTo("招牌辣椒鸡盖饭 1");
            assertThat(order.getTotal()).isEqualByComparingTo("21.80");
        });
        assertThat(jdbc.queryForObject("select previous_price from seed_dish_metadata where dish_name = ?", BigDecimal.class, "招牌辣椒鸡盖饭 1")).isEqualByComparingTo("16.80");
        assertThat(jdbc.queryForObject("select price_changed from seed_dish_metadata where dish_name = ?", Boolean.class, "招牌辣椒鸡盖饭 1")).isTrue();
        assertThat(jdbc.queryForObject("select valid from seed_address_metadata where detail = ?", Boolean.class, "已失效地址：演示市旧站路 9 号")).isFalse();
        assertThat(jdbc.queryForObject("select ingredients from seed_dish_metadata where dish_name = ?", String.class, "招牌辣椒鸡盖饭 1")).contains("鸡肉");
        assertThat(jdbc.queryForObject("select count(distinct category) from merchants", Long.class)).isEqualTo(7L);
    }

    @Test
    void repeatedExecutionDoesNotDuplicateRows() throws Exception {
        long merchantsBefore = merchants.count();
        long dishesBefore = dishes.count();
        loader.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(merchants.count()).isEqualTo(merchantsBefore);
        assertThat(dishes.count()).isEqualTo(dishesBefore);
    }
}
