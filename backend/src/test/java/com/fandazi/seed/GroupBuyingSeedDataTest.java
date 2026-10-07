// 团购种子测试：验证门店、套餐、评价、优惠券和订单状态覆盖达到演示规模。
package com.fandazi.seed;

import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreRepository;
import com.fandazi.feature.coupon.CouponRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import com.fandazi.feature.voucher.VoucherRepository;
import com.fandazi.feature.catalog.StoreReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证团购种子数据可重复装载，并覆盖后续浏览、购买、核销和售后流程所需的状态。
 *
 * <p>这些数量是演示环境的最低基线，测试故意只断言下限，允许未来增加数据而不改变业务契约。</p>
 */
@SpringBootTest(properties = {
        "seed.enabled=true",
        "group.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:group-buying-seed;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class GroupBuyingSeedDataTest {
    @Autowired private StoreRepository stores;
    @Autowired private DealPackageRepository deals;
    @Autowired private StoreReviewRepository reviews;
    @Autowired private CouponRepository coupons;
    @Autowired private GroupBuyOrderRepository orders;
    @Autowired private VoucherRepository vouchers;
    @Autowired private SeedDataLoader loader;
    @Autowired private com.fandazi.core.identity.PublicIdAliases publicIds;

    /** 同一数据库重新装载时 UUID、订单、券及引用必须保持，不能重新随机生成或重置历史业务状态。 */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void reloadingSeedsPreservesStableAliasesAndResourceRows() {
        var storeIds = stores.findAll().stream().map(store -> store.getPublicId()).sorted().toList();
        var dealIds = deals.findAll().stream().map(deal -> deal.getPublicId()).sorted().toList();
        var orderIds = orders.findAll().stream().map(order -> order.getPublicId()).sorted().toList();
        var voucherIds = vouchers.findAll().stream().map(voucher -> voucher.getPublicId()).sorted().toList();
        var aliases = publicIds.mappings("deal");
        loader.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        assertThat(stores.findAll().stream().map(store -> store.getPublicId()).sorted().toList()).isEqualTo(storeIds);
        assertThat(deals.findAll().stream().map(deal -> deal.getPublicId()).sorted().toList()).isEqualTo(dealIds);
        assertThat(orders.findAll().stream().map(order -> order.getPublicId()).sorted().toList()).isEqualTo(orderIds);
        assertThat(vouchers.findAll().stream().map(voucher -> voucher.getPublicId()).sorted().toList()).isEqualTo(voucherIds);
        assertThat(publicIds.mappings("deal")).isEqualTo(aliases);
    }

    /** 公开资源只能使用随机 UUID，确定性夹具别名不得出现在页面资源编号中。 */
    @Test
    void allPublishedSeedResourcesUseRandomUuids() {
        assertThat(stores.findAll()).allSatisfy(store -> assertRandomUuid(store.getPublicId()));
        assertThat(deals.findAll()).allSatisfy(deal -> assertRandomUuid(deal.getPublicId()));
        assertThat(orders.findAll()).allSatisfy(order -> assertRandomUuid(order.getPublicId()));
        assertThat(vouchers.findAll()).allSatisfy(voucher -> assertRandomUuid(voucher.getPublicId()));
        assertThat(coupons.findAll()).allSatisfy(coupon -> assertRandomUuid(coupon.getPublicId()));
    }

    /** UUID 第四版随机数防止从可猜测的旧编号推导到公开编号。 */
    private void assertRandomUuid(String value) {
        assertThat(java.util.UUID.fromString(value).version()).isEqualTo(4);
    }

    @Test
    void seedContainsGroupBuyingScaleAndAllVoucherStates() {
        // 门店、套餐和评价规模必须足以支持推荐与评价查询，不得只用一两条演示记录。
        assertThat(stores.count()).isGreaterThanOrEqualTo(30);
        assertThat(deals.count()).isGreaterThanOrEqualTo(120);
        assertThat(reviews.count()).isGreaterThanOrEqualTo(300);
        assertThat(coupons.count()).isGreaterThanOrEqualTo(30);

        // 订单和团购券必须覆盖待支付、待使用、部分使用、已使用、退款和过期场景。
        assertThat(orders.findAll()).extracting(order -> order.getStatus().name())
                .contains("PENDING_PAYMENT", "PAID", "PARTIALLY_USED", "USED", "REFUNDED", "EXPIRED");
        assertThat(vouchers.findAll()).extracting(voucher -> voucher.getStatus().name())
                .contains("UNUSED", "REDEEMED", "REFUNDED", "EXPIRED");
    }
}
