package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fandazi.feature.catalog.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "seed.enabled=false")
class SeedDisabledTest {
    @Autowired private MerchantRepository merchants;

    @Test
    void disabledSeedDoesNotLoadFixtures() {
        assertThat(merchants.count()).isZero();
    }
}
