// 收货地址模块：验证关键行为与边界条件。
package com.fandazi.feature.address;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AddressConcurrencyTest {
    @Autowired AddressService addresses;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate transactions;

    @Test
    void concurrentDefaultAddressWritesLeaveExactlyOneDefault() throws Exception {
        UserEntity user = users.save(new UserEntity("address-race", "address-race@example.com", "hash"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<AddressEntity>> writes = List.of(
                    () -> transactions.execute(status -> addresses.create(user.getId(), new AddressCommand("A", "13800000001", "一号", true))),
                    () -> transactions.execute(status -> addresses.create(user.getId(), new AddressCommand("B", "13800000002", "二号", true))));
            for (Future<AddressEntity> write : pool.invokeAll(writes)) {
                assertThat(write.get(10, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(addresses.list(user.getId())).filteredOn(AddressEntity::isDefaultAddress).hasSize(1);
    }
}
