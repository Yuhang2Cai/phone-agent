// 订单模块：验证关键行为与边界条件。
package com.fandazi.feature.order;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FandaziApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class CrossLayerOrderingFlowTest {
    @Autowired MockMvc mockMvc;
    @Autowired MerchantRepository merchants;
    @Autowired DishRepository dishes;
    @Autowired DishOptionRepository options;

    @Test
    void publicApiCompletesOrderingFlowAndPreservesOwnershipAndIdempotency() throws Exception {
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "跨层测试小馆", "盖饭", new BigDecimal("10.00"), new BigDecimal("3.00"), 24, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "盖饭", "番茄牛腩饭", "可选辣度", new BigDecimal("18.00"), 8, true, "/images/seed/meal-placeholder.png"));
        DishOptionEntity option = options.save(new DishOptionEntity(dish, "辣度", "微辣", new BigDecimal("1.00")));

        String ownerToken = register("cross-owner", "cross-owner@example.com");
        String strangerToken = register("cross-stranger", "cross-stranger@example.com");

        Long merchantId = JsonPath.parse(mockMvc.perform(get("/api/merchants?keyword=跨层").header("Authorization", bearer(ownerToken)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[0].name").value("跨层测试小馆"))
                        .andReturn().getResponse().getContentAsString())
                .read("$.content[0].id", Long.class);

        Long dishId = JsonPath.parse(mockMvc.perform(get("/api/merchants/{id}/dishes", merchantId).header("Authorization", bearer(ownerToken)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].name").value("番茄牛腩饭"))
                        .andExpect(jsonPath("$[0].options[0].optionName").value("微辣"))
                        .andReturn().getResponse().getContentAsString())
                .read("$[0].id", Long.class);

        Long addressId = JsonPath.parse(mockMvc.perform(post("/api/addresses").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"测试用户\",\"phone\":\"13800000000\",\"detail\":\"跨层路 1 号\",\"defaultAddress\":true}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.defaultAddress").value(true))
                        .andReturn().getResponse().getContentAsString())
                .read("$.id", Long.class);

        mockMvc.perform(patch("/api/addresses/{id}/default", addressId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAddress").value(true));

        Long cartId = JsonPath.parse(mockMvc.perform(post("/api/cart/drafts").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":" + merchantId + "}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .read("$.id", Long.class);

        mockMvc.perform(put("/api/cart/drafts/{id}/items", cartId).header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"dishId\":" + dishId + ",\"quantity\":1,\"optionIds\":[" + option.getId() + "],\"note\":\"不要香菜\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1));

        mockMvc.perform(post("/api/cart/drafts/{id}/validate", cartId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].dishName").value("番茄牛腩饭"))
                .andExpect(jsonPath("$.total").value(22.00));

        String orderBody = "{\"cartDraftId\":" + cartId + ",\"addressId\":" + addressId + "}";
        String created = mockMvc.perform(post("/api/orders").header("Authorization", bearer(ownerToken))
                        .header("Idempotency-Key", "cross-layer-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.timeline[0]").value("PENDING_PAYMENT"))
                .andExpect(content().string(not(containsString("password"))))
                .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.id", Long.class);

        mockMvc.perform(post("/api/orders").header("Authorization", bearer(ownerToken))
                        .header("Idempotency-Key", "cross-layer-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId));

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(strangerToken)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/orders/{id}/pay", orderId).header("Authorization", bearer(ownerToken))
                        .header("Idempotency-Key", "cross-layer-pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERCHANT_ACCEPTED"))
                .andExpect(jsonPath("$.timeline[0]").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.timeline[1]").value("PAID"))
                .andExpect(jsonPath("$.timeline[2]").value("MERCHANT_ACCEPTED"));

        mockMvc.perform(post("/api/orders/{id}/pay", orderId).header("Authorization", bearer(ownerToken))
                        .header("Idempotency-Key", "cross-layer-pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERCHANT_ACCEPTED"));
    }

    private String register(String username, String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(response).read("$.token", String.class);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
