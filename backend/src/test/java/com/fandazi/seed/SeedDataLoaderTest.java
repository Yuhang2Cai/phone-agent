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
        assertThat(orders.findAll())
                .anySatisfy(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID));
    }
}
