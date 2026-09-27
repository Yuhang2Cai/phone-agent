package com.fandazi.feature.catalog;

import com.fandazi.FandaziApplication;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FandaziApplication.class, properties = "seed.enabled=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MerchantCatalogControllerTest {
    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final BigDecimal ONE = new BigDecimal("1.00");

    @Autowired MockMvc mockMvc;
    @Autowired MerchantRepository merchantRepository;

    @Test
    void pagesOpenMerchantsInStableIdOrder() throws Exception {
        merchantRepository.save(new MerchantEntity("关门店", "快餐", TEN, ONE, 20, false));
        var first = merchantRepository.save(new MerchantEntity("甲店", "快餐", TEN, ONE, 20, true));
        var second = merchantRepository.save(new MerchantEntity("乙店", "快餐", TEN, ONE, 20, true));

        mockMvc.perform(get("/api/merchants").param("page", "0").param("size", "1").param("openOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first.getId()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.sort.sorted").value(true))
                .andExpect(jsonPath("$.last").value(false));
        mockMvc.perform(get("/api/merchants").param("page", "1").param("size", "1").param("openOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void getsMerchantByIdAndRejectsMissingId() throws Exception {
        var merchant = merchantRepository.save(new MerchantEntity("甲店", "快餐", TEN, ONE, 20, false));

        mockMvc.perform(get("/api/merchants/{id}", merchant.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(merchant.getId()))
                .andExpect(jsonPath("$.name").value("甲店"))
                .andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(get("/api/merchants/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsExistingFiltersAndIncludesClosedMerchantsByDefault() throws Exception {
        var matching = merchantRepository.save(new MerchantEntity("午餐甲店", "快餐", TEN, ONE, 20, false));
        merchantRepository.save(new MerchantEntity("晚餐店", "快餐", TEN, ONE, 20, true));
        merchantRepository.save(new MerchantEntity("午餐异类店", "面食", TEN, ONE, 20, true));
        merchantRepository.save(new MerchantEntity("午餐慢店", "快餐", TEN, ONE, 40, true));
        merchantRepository.save(new MerchantEntity("午餐贵店", "快餐", new BigDecimal("30.00"), ONE, 20, true));

        mockMvc.perform(get("/api/merchants")
                        .param("keyword", "午餐")
                        .param("category", "快餐")
                        .param("maxDeliveryMinutes", "20")
                        .param("maxPrice", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(matching.getId()))
                .andExpect(jsonPath("$.content[0].open").value(false));
    }
}
