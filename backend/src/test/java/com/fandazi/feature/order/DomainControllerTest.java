package com.fandazi.feature.order;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import java.math.BigDecimal;
import java.util.List;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = FandaziApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class DomainControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired MerchantRepository merchants;
    @Autowired DishRepository dishes;
    @Autowired DishOptionRepository options;
    @Autowired AddressService addresses;
    @Autowired CartService carts;
    @Autowired OrderService orders;

    @Test
    void dishMenuResponseIncludesSelectableOptionsWithoutLeakingEntities() throws Exception {
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "规格餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "规格盖饭", "", new BigDecimal("12.00"), 10, true, ""));
        DishOptionEntity option = options.save(new DishOptionEntity(dish, "辣度", "中辣", new BigDecimal("2.00")));
        UserEntity user = users.save(new UserEntity("menu-user", "menu-user@example.com", "secret-hash"));
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, java.util.List.of()));

        mockMvc.perform(get("/api/merchants/{id}/dishes", merchant.getId()).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dish.getId()))
                .andExpect(jsonPath("$[0].options[0].id").value(option.getId()))
                .andExpect(jsonPath("$[0].options[0].groupName").value("辣度"))
                .andExpect(jsonPath("$[0].options[0].priceDelta").value(2.00))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void addressAndOrderResponsesDoNotExposeUserEntityOrPasswordHash() throws Exception {
        UserEntity user = users.save(new UserEntity("dto-user", "dto-user@example.com", "secret-hash"));
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, java.util.List.of()));

        mockMvc.perform(post("/api/addresses").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"DTO User\",\"phone\":\"13800000000\",\"detail\":\"DTO 路 1 号\",\"defaultAddress\":true}"))
                .andExpect(status().isCreated())
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("password_hash"))));
        mockMvc.perform(get("/api/addresses").with(auth))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("password_hash"))));
        mockMvc.perform(get("/api/orders/999999").with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void orderDetailLoadsTimelineAndOptionSnapshotBeforeBuildingResponse() throws Exception {
        UserEntity user = users.save(new UserEntity("order-detail-user", "order-detail-user@example.com", "secret-hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "订单餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "订单盖饭", "", new BigDecimal("12.00"), 10, true, ""));
        DishOptionEntity option = options.save(new DishOptionEntity(dish, "辣度", "中辣", new BigDecimal("2.00")));
        AddressEntity address = addresses.create(user.getId(), new AddressCommand("Detail", "13800000000", "详情路", true));
        CartDraft cart = carts.createDraft(user.getId(), merchant.getId());
        carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(option.getId()), null)));
        OrderEntity order = orders.create(user.getId(), new CreateOrderCommand(cart.getId(), address.getId(), "detail-order"));
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(get("/api/orders/{id}", order.getId()).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline[0]").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].optionIds[0]").value(option.getId()))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void orderCreateAndPaymentResponsesAreInitializedForFirstAndIdempotentRequests() throws Exception {
        UserEntity user = users.save(new UserEntity("http-idem-user", "http-idem-user@example.com", "secret-hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "HTTP 餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "HTTP 盖饭", "", new BigDecimal("12.00"), 10, true, ""));
        DishOptionEntity option = options.save(new DishOptionEntity(dish, "辣度", "中辣", new BigDecimal("2.00")));
        AddressEntity address = addresses.create(user.getId(), new AddressCommand("HTTP User", "13800000000", "HTTP 路 1 号", true));
        CartDraft cart = carts.createDraft(user.getId(), merchant.getId());
        carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(option.getId()), null)));
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
        String body = "{\"cartDraftId\":" + cart.getId() + ",\"addressId\":" + address.getId() + "}";

        var firstCreate = mockMvc.perform(post("/api/orders").with(auth)
                        .header("Idempotency-Key", "http-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.timeline[0]").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].optionIds[0]").value(option.getId()))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andReturn();
        Long orderId = JsonPath.parse(firstCreate.getResponse().getContentAsString()).read("$.id", Long.class);

        mockMvc.perform(post("/api/orders").with(auth)
                        .header("Idempotency-Key", "http-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.timeline[0]").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].optionIds[0]").value(option.getId()))
                .andExpect(content().string(not(containsString("passwordHash"))));

        mockMvc.perform(post("/api/orders/{id}/pay", orderId).with(auth)
                        .header("Idempotency-Key", "http-pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERCHANT_ACCEPTED"))
                .andExpect(jsonPath("$.timeline[2]").value("MERCHANT_ACCEPTED"))
                .andExpect(jsonPath("$.items[0].optionIds[0]").value(option.getId()))
                .andExpect(content().string(not(containsString("passwordHash"))));

        mockMvc.perform(post("/api/orders/{id}/pay", orderId).with(auth)
                        .header("Idempotency-Key", "http-pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERCHANT_ACCEPTED"))
                .andExpect(jsonPath("$.timeline[2]").value("MERCHANT_ACCEPTED"))
                .andExpect(jsonPath("$.items[0].optionIds[0]").value(option.getId()))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void userCannotCallInternalDeliveryTransitionEndpoint() throws Exception {
        var auth = authentication(new UsernamePasswordAuthenticationToken(1L, null, java.util.List.of()));
        mockMvc.perform(post("/api/orders/1/delivery").with(auth)
                        .contentType("application/json").content("{\"status\":\"PREPARING\"}"))
                .andExpect(status().is4xxClientError());
    }
}
