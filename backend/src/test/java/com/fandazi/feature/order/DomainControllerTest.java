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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void orderListReturnsOnlyCurrentUsersOrdersWithTimelineSnapshots() throws Exception {
        UserEntity owner = users.save(new UserEntity("order-list-owner", "order-list-owner@example.com", "secret-hash"));
        UserEntity other = users.save(new UserEntity("order-list-other", "order-list-other@example.com", "secret-hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "列表餐厅", "粥汤", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "粥", "南瓜小米粥", "", new BigDecimal("12.00"), 10, true, ""));
        AddressEntity ownerAddress = addresses.create(owner.getId(), new AddressCommand("Owner", "13800000000", "列表路 1 号", true));
        AddressEntity otherAddress = addresses.create(other.getId(), new AddressCommand("Other", "13800000001", "列表路 2 号", true));
        CartDraft ownerCart = carts.createDraft(owner.getId(), merchant.getId());
        CartDraft otherCart = carts.createDraft(other.getId(), merchant.getId());
        carts.replaceItems(owner.getId(), ownerCart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
        carts.replaceItems(other.getId(), otherCart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
        OrderEntity ownerOrder = orders.create(owner.getId(), new CreateOrderCommand(ownerCart.getId(), ownerAddress.getId(), "owner-list-order"));
        orders.create(other.getId(), new CreateOrderCommand(otherCart.getId(), otherAddress.getId(), "other-list-order"));
        var ownerAuth = authentication(new UsernamePasswordAuthenticationToken(owner.getId(), null, List.of()));

        mockMvc.perform(get("/api/orders").with(ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ownerOrder.getId()))
                .andExpect(jsonPath("$[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].timeline[0]").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].items[0].dishName").value("南瓜小米粥"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(content().string(not(containsString("other-list-order"))))
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

    @Test
    void rejectsInvalidHttpInputsWithStableBadRequest() throws Exception {
        UserEntity user = users.save(new UserEntity("validation-user", "validation@example.com", "secret-hash"));
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(post("/api/addresses").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"\",\"phone\":\"\",\"detail\":\"\",\"defaultAddress\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
        mockMvc.perform(post("/api/orders").with(auth)
                        .header("Idempotency-Key", "validation-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartDraftId\":null,\"addressId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
        mockMvc.perform(put("/api/cart/drafts/1/items").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"dishId\":null,\"quantity\":0,\"optionIds\":[]}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
        mockMvc.perform(post("/api/orders").with(auth)
                        .header("Idempotency-Key", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartDraftId\":1,\"addressId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolatesOrdersAcrossUsersAndRejectsHttpIdempotencyConflict() throws Exception {
        UserEntity owner = users.save(new UserEntity("http-owner", "http-owner@example.com", "hash"));
        UserEntity stranger = users.save(new UserEntity("http-stranger", "http-stranger@example.com", "hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity(
                "隔离餐厅", "盖饭", new BigDecimal("10.00"), new BigDecimal("2.00"), 25, true));
        DishEntity dish = dishes.save(new DishEntity(
                merchant, "主食", "隔离盖饭", "", new BigDecimal("12.00"), 10, true, ""));
        AddressEntity ownerAddress = addresses.create(owner.getId(), new AddressCommand("Owner", "13800000000", "隔离路 1 号", true));
        AddressEntity otherAddress = addresses.create(owner.getId(), new AddressCommand("Owner", "13800000000", "隔离路 2 号", false));
        CartDraft cart = carts.createDraft(owner.getId(), merchant.getId());
        carts.replaceItems(owner.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
        var ownerAuth = authentication(new UsernamePasswordAuthenticationToken(owner.getId(), null, List.of()));
        var strangerAuth = authentication(new UsernamePasswordAuthenticationToken(stranger.getId(), null, List.of()));

        var created = mockMvc.perform(post("/api/orders").with(ownerAuth)
                        .header("Idempotency-Key", "http-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartDraftId\":" + cart.getId() + ",\"addressId\":" + ownerAddress.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long orderId = JsonPath.parse(created.getResponse().getContentAsString()).read("$.id", Long.class);

        mockMvc.perform(get("/api/orders/{id}", orderId).with(strangerAuth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").with(ownerAuth)
                        .header("Idempotency-Key", "http-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartDraftId\":" + cart.getId() + ",\"addressId\":" + otherAddress.getId() + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidDraftMerchantAndMerchantPagingParameters() throws Exception {
        UserEntity user = users.save(new UserEntity("paging-user", "paging@example.com", "hash"));
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(post("/api/cart/drafts").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
        mockMvc.perform(get("/api/merchants?page=-1").with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
        mockMvc.perform(get("/api/merchants?size=0").with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
        mockMvc.perform(get("/api/merchants?size=101").with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求参数无效"));
    }
}
