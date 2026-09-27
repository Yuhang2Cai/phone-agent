package com.fandazi.feature.catalog;

import com.fandazi.FandaziApplication;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired JdbcTemplate jdbc;

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

    @Test
    void pagesOnlyRequestedMerchantReviewsByTimestampThenDescendingId() throws Exception {
        var merchant = merchantRepository.save(new MerchantEntity("甲店", "快餐", TEN, ONE, 20, true));
        var other = merchantRepository.save(new MerchantEntity("乙店", "快餐", TEN, ONE, 20, true));
        var sameTime = Instant.parse("2026-09-27T08:00:00Z");
        insertReview(merchant.getId(), "旧评", "3.0", "先前评价", sameTime.minusSeconds(60));
        insertReview(merchant.getId(), "小饭", "4.8", "味道很好", sameTime);
        insertReview(merchant.getId(), "小圆", "4.5", "送达准时", sameTime);
        insertReview(other.getId(), "别家客", "5.0", "别家评价", sameTime.plusSeconds(60));

        mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].merchantId").value(merchant.getId()))
                .andExpect(jsonPath("$.content[0].reviewerName").value("小圆"))
                .andExpect(jsonPath("$.content[0].rating").value(4.5))
                .andExpect(jsonPath("$.content[0].content").value("送达准时"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-09-27T08:00:00Z"))
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.number").value(0));
        mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewerName").value("小饭"));
        mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewerName").value("旧评"));
    }

    @Test
    void rejectsInvalidReviewPagingAndMissingMerchant() throws Exception {
        var merchant = merchantRepository.save(new MerchantEntity("甲店", "快餐", TEN, ONE, 20, true));

        mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/merchants/{id}/reviews", merchant.getId()).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/merchants/{id}/reviews", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    private void insertReview(Long merchantId, String reviewerName, String rating, String content, Instant createdAt) {
        jdbc.update("insert into merchant_reviews (merchant_id, reviewer_name, rating, content, created_at) values (?, ?, ?, ?, ?)",
                merchantId, reviewerName, new BigDecimal(rating), content, Timestamp.from(createdAt));
    }
}
