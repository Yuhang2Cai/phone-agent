package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.automation.AutomationPolicyService;
import com.fandazi.feature.automation.OrderAuthorizationService;
import com.fandazi.feature.cart.CartDraft;
import com.fandazi.feature.cart.CartItemCommand;
import com.fandazi.feature.cart.CartService;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AutomationPolicyTest {
    @Autowired private AutomationPolicyService policies;
    @Autowired private OrderAuthorizationService authorizations;
    @Autowired private AgentSessionService sessions;
    @Autowired private AddressService addresses;
    @Autowired private CartService carts;
    @Autowired private UserRepository users;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;

    private UserEntity user;
    private AddressEntity address;
    private CartDraft cart;

    @BeforeEach
    void setUp() {
        user = users.save(new UserEntity("automation-user", "automation@example.com", "hash"));
        MerchantEntity merchant = merchants.save(new MerchantEntity("自动餐厅", "盖饭", new BigDecimal("10"), new BigDecimal("2"), 20, true));
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "自动套餐", "", new BigDecimal("20"), 3, true, ""));
        address = addresses.create(user.getId(), new AddressCommand("Auto", "13800000000", "自动路 1 号", true));
        cart = carts.createDraft(user.getId(), merchant.getId());
        carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
    }

    @Test
    void permitsLongTermPolicyOnlyWithinItsAddressAndAmountLimit() {
        policies.upsert(user.getId(), true, address.getId(), new BigDecimal("25"));
        AgentSession session = sessions.start(user.getId(), "long-policy", AgentMode.THIS_TASK_AUTO, "trace");

        var authorized = authorizations.authorize(user.getId(), session, cart.getId(), address.getId());

        assertThat(authorized.quote().total()).isEqualByComparingTo("22.00");
        assertThatThrownBy(() -> authorizations.authorize(user.getId(), session, cart.getId(), address.getId() + 99))
                .hasMessageContaining("地址");
    }

    @Test
    void rejectsThisTaskGrantAfterExpiryOrAmountOverrun() {
        AgentSession session = sessions.start(user.getId(), "short-grant", AgentMode.THIS_TASK_AUTO, "trace");
        policies.grantForTask(user.getId(), session.getSessionId(), address.getId(), new BigDecimal("21"), Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> authorizations.authorize(user.getId(), session, cart.getId(), address.getId()))
                .hasMessageContaining("授权");

        policies.grantForTask(user.getId(), session.getSessionId(), address.getId(), new BigDecimal("21"), Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> authorizations.authorize(user.getId(), session, cart.getId(), address.getId()))
                .hasMessageContaining("金额");
    }
}
