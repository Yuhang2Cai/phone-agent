// 智能助手模块：验证关键行为与边界条件。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
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
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AutomationDefaultAddressTest {
    @Autowired private AutomationPolicyService policies;
    @Autowired private AgentSessionService sessions;
    @Autowired private AgentToolService tools;
    @Autowired private UserRepository users;
    @Autowired private AddressService addresses;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private CartService carts;

    private UserEntity user;
    private AddressEntity originalDefault;

    @BeforeEach
    void setUp() {
        user = users.save(new UserEntity("default-owner", "default-owner@example.com", "hash"));
        originalDefault = addresses.create(user.getId(), new AddressCommand("Owner", "13800000000", "原默认地址", true));
        merchants.save(new MerchantEntity("默认地址餐厅", "盖饭", new BigDecimal("10"), new BigDecimal("2"), 20, true));
    }

    @Test
    void rejectsLongTermPolicyConfiguredWithANonDefaultAddress() {
        AddressEntity nonDefault = addresses.create(user.getId(), new AddressCommand("Other", "13900000000", "非默认地址", false));

        assertThatThrownBy(() -> policies.upsert(user.getId(), true, nonDefault.getId(), new BigDecimal("30")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("默认地址");
    }

    @Test
    void rejectsLongTermPolicyOrderAfterTheSavedAddressStopsBeingDefault() {
        policies.upsert(user.getId(), true, originalDefault.getId(), new BigDecimal("30"));
        AddressEntity replacement = addresses.create(user.getId(), new AddressCommand("Other", "13900000000", "新默认地址", false));
        addresses.setDefault(user.getId(), replacement.getId());
        AgentSession session = sessions.start(user.getId(), "policy-default-change", AgentMode.THIS_TASK_AUTO, "policy-trace");

        assertThatThrownBy(() -> tools.execute("create_order", new ToolRequest("forged", session.getSessionId(), "forged", "policy-order", "policy-workflow",
                Map.of("cartDraftId", cartWithDish().getId(), "addressId", originalDefault.getId()))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("自动授权");
    }

    private CartDraft cartWithDish() {
        MerchantEntity merchant = merchants.findAll().get(0);
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "默认地址套餐", "", new BigDecimal("20"), 3, true, ""));
        CartDraft cart = carts.createDraft(user.getId(), merchant.getId());
        return carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
    }
}
