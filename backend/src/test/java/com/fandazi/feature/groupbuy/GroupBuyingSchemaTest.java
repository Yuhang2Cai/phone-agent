// 团购领域测试：验证新迁移、公开标识和一券一数量的状态机基础规则。
package com.fandazi.feature.groupbuy;

import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderStatus;
import com.fandazi.feature.voucher.VoucherStatus;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证团购表已经由 Flyway 创建，并验证订单数量与团购券数量的一致性。
 *
 * <p>状态机测试使用内存对象，避免把“每个购买数量生成一张券”的领域规则
 * 绑定到后续订单服务；数据库测试则通过 Spring 启动时的 Hibernate 校验确认映射与迁移一致。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:group-buying-schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "seed.enabled=false",
        "security.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
        "agent.internal-token=test-agent-token"
})
class GroupBuyingSchemaTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void groupBuyingTablesAreCreatedByFlyway() {
        // 通过 INFORMATION_SCHEMA 检查迁移是否真正创建了团购核心表，而不是依赖 Hibernate 自动建表。
        assertThat(tableCount("STORES")).isEqualTo(1);
        assertThat(tableCount("DEAL_PACKAGES")).isEqualTo(1);
        assertThat(tableCount("GROUP_BUY_ORDERS")).isEqualTo(1);
        assertThat(tableCount("VOUCHERS")).isEqualTo(1);
        assertThat(tableCount("PURCHASE_QUOTES")).isEqualTo(1);
        assertThat(tableCount("CONFIRMATION_GRANTS")).isEqualTo(1);
    }

    /** 合法套餐购买三份时生成三张券，并固定同一使用起日，防止数量规则或权益快照回归。 */
    @Test
    void onePurchasedQuantityCreatesOneVoucher() {
        // 一个订单购买三份固定套餐时，领域对象必须先建立三张未使用券，后续核销按券粒度进行。
        // 套餐为生产订单的必需关联；显式提供起日，不能用空对象绕过新券权益快照所需的输入。
        LocalDate validFrom = LocalDate.of(2026, 10, 29);
        DealPackageEntity deal = new DealPackageEntity("quantity-deal", null, "数量测试套餐", "合成测试数据",
                1000, 1500, 10, validFrom, validFrom.plusDays(1), true, 3, 1);
        GroupBuyOrderEntity order = new GroupBuyOrderEntity(
                "group-order-fixture",
                null,
                null,
                deal,
                3,
                3000,
                GroupBuyOrderStatus.PAID,
                validFrom.plusDays(2).atStartOfDay()
        );

        assertThat(order.getVouchers()).hasSize(3);
        assertThat(order.getVouchers()).allMatch(v -> v.getStatus() == VoucherStatus.UNUSED);
        assertThat(order.getVouchers()).allMatch(v -> validFrom.equals(v.getValidFrom()));
    }

    private int tableCount(String tableName) {
        // H2 在 MySQL 模式下会把未加引号的表名转为大写，因此统一使用大写查询迁移结果。
        return jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?",
                Integer.class,
                tableName
        );
    }
}
