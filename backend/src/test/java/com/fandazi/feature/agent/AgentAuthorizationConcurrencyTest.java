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
import com.fandazi.feature.order.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AgentAuthorizationConcurrencyTest {
    @Autowired private AgentToolService tools;
    @Autowired private AgentSessionService sessions;
    @Autowired private AutomationPolicyService policies;
    @Autowired private UserRepository users;
    @Autowired private AddressService addresses;
    @Autowired private MerchantRepository merchants;
    @Autowired private DishRepository dishes;
    @Autowired private CartService carts;
    @Autowired private OrderRepository orders;

    private UserEntity user;
    private AddressEntity address;

    @BeforeEach
    void setUp() {
        user = users.save(new UserEntity("parallel-owner", "parallel-owner@example.com", "hash"));
        address = addresses.create(user.getId(), new AddressCommand("Owner", "13800000000", "并发地址", true));
        merchants.save(new MerchantEntity("并发餐厅", "盖饭", new BigDecimal("10"), new BigDecimal("2"), 20, true));
    }

    @Test
    void aConfirmationCanAuthorizeOnlyOneConcurrentWorkflowOrder() throws Exception {
        AgentSession session = sessions.start(user.getId(), "parallel-confirm", AgentMode.CONFIRM, "confirm-trace");
        sessions.confirm(user.getId(), session.getSessionId());

        List<Outcome> results = concurrentlyCreate(session, cartWithDish(), cartWithDish(), "confirm-a", "confirm-b");

        assertThat(results).filteredOn(Outcome::success).hasSize(1);
        assertThat(results).filteredOn(outcome -> outcome.failure() instanceof DomainException).hasSize(1);
        assertThat(orders.findAll()).hasSize(1);
    }

    @Test
    void aSingleTaskGrantCanAuthorizeOnlyOneConcurrentWorkflowOrder() throws Exception {
        AgentSession session = sessions.start(user.getId(), "parallel-grant", AgentMode.THIS_TASK_AUTO, "grant-trace");
        policies.grantForTask(user.getId(), session.getSessionId(), address.getId(), new BigDecimal("30"), Instant.now().plusSeconds(300));

        List<Outcome> results = concurrentlyCreate(session, cartWithDish(), cartWithDish(), "grant-a", "grant-b");

        assertThat(results).filteredOn(Outcome::success).hasSize(1);
        assertThat(results).filteredOn(outcome -> outcome.failure() instanceof DomainException).hasSize(1);
        assertThat(orders.findAll()).hasSize(1);
    }

    private CartDraft cartWithDish() {
        MerchantEntity merchant = merchants.findAll().get(0);
        DishEntity dish = dishes.save(new DishEntity(merchant, "主食", "并发套餐", "", new BigDecimal("20"), 10, true, ""));
        CartDraft cart = carts.createDraft(user.getId(), merchant.getId());
        return carts.replaceItems(user.getId(), cart.getId(), List.of(new CartItemCommand(dish.getId(), 1, List.of(), null)));
    }

    private List<Outcome> concurrentlyCreate(AgentSession session, CartDraft first, CartDraft second, String firstWorkflow, String secondWorkflow) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Outcome>> calls = List.of(
                    createCall(session, first.getId(), firstWorkflow, ready, start),
                    createCall(session, second.getId(), secondWorkflow, ready, start));
            List<Future<Outcome>> futures = calls.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Outcome> createCall(AgentSession session, Long cartId, String workflowId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                tools.execute("create_order", new ToolRequest("forged", session.getSessionId(), "forged", workflowId + "-key", workflowId,
                        Map.of("cartDraftId", cartId, "addressId", address.getId())));
                return new Outcome(true, null);
            } catch (RuntimeException exception) {
                return new Outcome(false, exception);
            }
        };
    }

    private record Outcome(boolean success, RuntimeException failure) {}
}
