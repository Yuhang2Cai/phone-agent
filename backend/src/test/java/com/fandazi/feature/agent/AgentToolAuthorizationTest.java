package com.fandazi.feature.agent;

import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.automation.AutomationPolicyService;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.order.CreateOrderCommand;
import com.fandazi.feature.order.OrderService;
import com.fandazi.feature.order.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AgentToolAuthorizationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentSessionService sessions;
    @Autowired private AutomationPolicyService policies;
    @Autowired private UserRepository users;
    @Autowired private AddressService addresses;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private DishOptionRepository options;
    @Autowired private CartService carts;
    @Autowired private OrderService orders;

    private UserEntity owner;
    private UserEntity attacker;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        owner = users.save(new UserEntity("tool-owner", "tool-owner@example.com", "hash"));
        attacker = users.save(new UserEntity("tool-attacker", "tool-attacker@example.com", "hash"));
        AddressEntity ownerAddress = addresses.create(owner.getId(), new AddressCommand("Owner", "13800000000", "所有者地址", true));
        addresses.create(attacker.getId(), new AddressCommand("Attacker", "13900000000", "攻击者地址", true));
        merchants.save(new MerchantEntity("工具餐厅", "盖饭", new BigDecimal("10"), new BigDecimal("2"), 20, true));
        session = sessions.start(owner.getId(), "tool-session", AgentMode.THIS_TASK_AUTO, "server-trace");
        policies.grantForTask(owner.getId(), session.getSessionId(), ownerAddress.getId(), new BigDecimal("30"), Instant.now().plusSeconds(300));
    }

    @Test
    void refusesMissingTokenAndAnyToolOutsideTheEightNameAllowlist() throws Exception {
        mockMvc.perform(post("/internal/tools/get_addresses").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/tools/delete_everything").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void derivesIdentityFromServerSessionInsteadOfModelSuppliedUserAndKeepsRequestsIdempotent() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", attacker.getId().toString(), "sessionId", session.getSessionId(), "traceId", "forged-trace",
                "idempotencyKey", "addresses-1", "payload", Map.of()));

        mockMvc.perform(post("/internal/tools/get_addresses").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(content().string(containsString("所有者地址")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("攻击者地址"))));
    }

    @Test
    void createsOneAutoOrderForRepeatedToolRequestAfterServerSideAuthorization() throws Exception {
        CartDraft cart = cartWithDish();
        String body = toolBody("create-order", Map.of("cartDraftId", cart.getId(), "addressId", ownerAddressId()));

        var first = mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andReturn();
        var second = mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();

        assertThat(objectMapper.readTree(second.getResponse().getContentAsString()).at("/data/order/id").asLong())
                .isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()).at("/data/order/id").asLong());
    }

    @Test
    void permitsConfirmedOrderButTerminatesReorderWhenItsDishHasBeenUnlisted() throws Exception {
        CartDraft cart = cartWithDish();
        AgentSession confirmSession = sessions.start(owner.getId(), "confirm-session", AgentMode.CONFIRM, "trace");
        bindAuthorizedCart(confirmSession, "trace", cart);
        sessions.confirm(owner.getId(), confirmSession.getSessionId());
        String confirmed = toolBody(confirmSession, "confirmed-order", Map.of("cartDraftId", cart.getId(), "addressId", ownerAddressId()));
        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmed)).andExpect(status().isOk());

        DishEntity dish = dishes.findAll().get(0);
        org.springframework.test.util.ReflectionTestUtils.setField(dish, "listed", false);
        dishes.saveAndFlush(dish);
        String lastOrder = toolBody("last-order", Map.of());
        mockMvc.perform(post("/internal/tools/get_last_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(lastOrder))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("下架")));
    }

    @Test
    void consumesConfirmedAuthorizationSoItCannotCreateAnotherCartOrder() throws Exception {
        CartDraft firstCart = cartWithDish();
        CartDraft secondCart = cartWithDish();
        AgentSession confirmSession = sessions.start(owner.getId(), "single-confirm-session", AgentMode.CONFIRM, "trace");
        bindAuthorizedCart(confirmSession, "trace", firstCart);
        sessions.confirm(owner.getId(), confirmSession.getSessionId());

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody(confirmSession, "first-confirmed-order", Map.of("cartDraftId", firstCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody(confirmSession, "second-confirmed-order", Map.of("cartDraftId", secondCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("确认")));
    }

    @Test
    void confirmedAuthorizationRejectsCartReplacementAfterUserSawAnotherCart() throws Exception {
        CartDraft displayedCart = cartWithDish();
        CartDraft replacedCart = cartWithDish();
        AgentSession confirmSession = sessions.start(owner.getId(), "bound-confirm-session", AgentMode.CONFIRM, "trace");
        bindAuthorizedCart(confirmSession, "bound-workflow", displayedCart);
        sessions.confirm(owner.getId(), confirmSession.getSessionId());

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody(confirmSession, "replaced-confirmed-order", "bound-workflow",
                                Map.of("cartDraftId", replacedCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("授权")));
    }

    @Test
    void thisTaskAutoGrantRejectsCartReplacementAfterUserAuthorizedAnotherCart() throws Exception {
        CartDraft displayedCart = cartWithDish();
        CartDraft replacedCart = cartWithDish();
        bindAuthorizedCart(session, "auto-workflow", displayedCart);

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody(session, "replaced-auto-order", "auto-workflow",
                                Map.of("cartDraftId", replacedCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("授权")));
    }

    @Test
    void returnsExistingAgentOrderBeforeRecheckingStockOrAuthorization() throws Exception {
        MerchantEntity merchant = merchants.findAll().get(0);
        DishEntity onlyDish = dishes.save(new DishEntity(merchant, "主食", "最后一份", "", new BigDecimal("20"), 1, true, ""));
        CartDraft cart = carts.createDraft(owner.getId(), merchant.getId());
        carts.replaceItems(owner.getId(), cart.getId(), java.util.List.of(new CartItemCommand(onlyDish.getId(), 1, java.util.List.of(), null)));
        String body = toolBody("recovery-order", Map.of("cartDraftId", cart.getId(), "addressId", ownerAddressId()));

        var first = mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        var retry = mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()).at("/data/order/id").asLong())
                .isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()).at("/data/order/id").asLong());
    }

    @Test
    void rejectsIdempotentRecoveryWhenTheExistingOrderHasAnotherCartOrAddress() throws Exception {
        CartDraft firstCart = cartWithDish();
        CartDraft otherCart = cartWithDish();
        String idempotencyKey = "fingerprint-conflict";

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody(idempotencyKey, Map.of("cartDraftId", firstCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody(idempotencyKey, Map.of("cartDraftId", otherCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("幂等键")));
    }

    @Test
    void autoPaymentRejectsAnOrderNotCreatedByTheCurrentSession() throws Exception {
        CartDraft cart = cartWithDish();
        OrderResponse otherOrder = orders.createResponse(owner.getId(), new CreateOrderCommand(cart.getId(), ownerAddressId(), "manual-order"));

        mockMvc.perform(post("/internal/tools/pay_mock_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("wrong-payment", Map.of("orderId", otherOrder.id()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("当前会话")));
    }

    @Test
    void consumesSingleTaskGrantAfterBoundOrderIsPaidAndRejectsAnotherOrder() throws Exception {
        CartDraft firstCart = cartWithDish();
        CartDraft secondCart = cartWithDish();
        var created = mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("single-auto-order", Map.of("cartDraftId", firstCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isOk()).andReturn();
        Long orderId = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/order/id").asLong();

        mockMvc.perform(post("/internal/tools/pay_mock_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("single-auto-payment", Map.of("orderId", orderId))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/tools/create_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("second-single-auto-order", Map.of("cartDraftId", secondCart.getId(), "addressId", ownerAddressId()))))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("授权")));
    }

    @Test
    void createCartDraftRejectsMissingExplicitMerchantInsteadOfChoosingFirstOpenMerchant() throws Exception {
        DishEntity dish = dishes.save(new DishEntity(merchants.findAll().get(0), "主食", "工具套餐", "", new BigDecimal("20"), 3, true, ""));

        mockMvc.perform(post("/internal/tools/create_cart_draft").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("draft-no-merchant", Map.of("items", java.util.List.of(Map.of("dishId", dish.getId(), "quantity", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("商家")));
    }

    @Test
    void createCartDraftRejectsMissingExplicitItemsInsteadOfChoosingFirstListedDish() throws Exception {
        Long merchantId = merchants.findAll().get(0).getId();
        dishes.save(new DishEntity(merchants.findAll().get(0), "主食", "工具套餐", "", new BigDecimal("20"), 3, true, ""));

        mockMvc.perform(post("/internal/tools/create_cart_draft").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("draft-no-items", Map.of("merchantId", merchantId))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("菜品")));
    }

    @Test
    void createCartDraftUsesExplicitMerchantAndItemPayload() throws Exception {
        MerchantEntity merchant = merchants.findAll().get(0);
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "工具套餐", "", new BigDecimal("20"), 3, true, ""));

        mockMvc.perform(post("/internal/tools/create_cart_draft").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolBody("draft-explicit", Map.of("merchantId", merchant.getId(),
                                "items", java.util.List.of(Map.of("dishId", dish.getId(), "quantity", 2))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.merchantId").value(merchant.getId()));
    }

    @Test
    void terminatesReorderWhenAnOriginalDishOptionIsNoLongerAvailable() throws Exception {
        MerchantEntity merchant = merchants.findAll().get(0);
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "规格套餐", "", new BigDecimal("20"), 3, true, ""));
        DishOptionEntity option = options.save(new DishOptionEntity(dish, "辣度", "微辣", BigDecimal.ZERO));
        CartDraft cart = carts.createDraft(owner.getId(), merchant.getId());
        carts.replaceItems(owner.getId(), cart.getId(), java.util.List.of(new CartItemCommand(dish.getId(), 1, java.util.List.of(option.getId()), null)));
        orders.create(owner.getId(), new com.fandazi.feature.order.CreateOrderCommand(cart.getId(), ownerAddressId(), "option-reorder"));
        org.springframework.test.util.ReflectionTestUtils.setField(option, "available", false);
        options.saveAndFlush(option);

        mockMvc.perform(post("/internal/tools/get_last_order").header("X-Agent-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON).content(toolBody("option-last-order", Map.of())))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("规格")));
    }

    private CartDraft cartWithDish() {
        MerchantEntity merchant = merchants.findAll().get(0);
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "工具套餐", "", new BigDecimal("20"), 3, true, ""));
        CartDraft cart = carts.createDraft(owner.getId(), merchant.getId());
        return carts.replaceItems(owner.getId(), cart.getId(), java.util.List.of(new CartItemCommand(dish.getId(), 1, java.util.List.of(), null)));
    }

    private void bindAuthorizedCart(AgentSession value, String workflowId, CartDraft cart) {
        bindAuthorizedCart(value, workflowId, cart.getId());
    }

    private void bindAuthorizedCart(AgentSession value, String workflowId, Long cartId) {
        sessions.bindAuthorizedCart(value, workflowId, cartId, ownerAddressId(), carts.validate(owner.getId(), cartId).total());
    }

    private Long ownerAddressId() { return addresses.list(owner.getId()).get(0).getId(); }
    private String toolBody(String key, Map<String, Object> payload) throws Exception { return toolBody(session, key, payload); }
    private String toolBody(AgentSession value, String key, Map<String, Object> payload) throws Exception {
        if (value.getMode() == AgentMode.THIS_TASK_AUTO && payload.get("cartDraftId") instanceof Number cartId) {
            bindAuthorizedCart(value, value.getTraceId(), cartId.longValue());
        }
        return objectMapper.writeValueAsString(Map.of("userId", attacker.getId().toString(), "sessionId", value.getSessionId(),
                "traceId", "forged-trace", "idempotencyKey", key, "payload", payload));
    }
    private String toolBody(AgentSession value, String key, String workflowId, Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(Map.of("userId", attacker.getId().toString(), "sessionId", value.getSessionId(),
                "traceId", "forged-trace", "idempotencyKey", key, "workflowId", workflowId, "payload", payload));
    }
}
