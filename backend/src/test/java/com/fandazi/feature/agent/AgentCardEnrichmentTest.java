// Agent 卡片补全测试：防止客户端直接信任模型提交的业务字段。
package com.fandazi.feature.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.DealPackageService;
import com.fandazi.feature.catalog.StoreEntity;
import com.fandazi.feature.catalog.StoreService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentCardEnrichmentTest {
    @Test
    void storeCardUsesSpringStoreFieldsInsteadOfModelPrice() {
        StoreService stores = mock(StoreService.class);
        DealPackageService deals = mock(DealPackageService.class);
        StoreEntity store = new StoreEntity("store-001", "品牌", "真实门店", "demo", "真实地址", "010", "川菜",
                BigDecimal.ONE, BigDecimal.ONE, 6800, true, "10:00-22:00", null);
        when(stores.require("store-001")).thenReturn(store);

        Map<String, Object> result = new AgentCardEnrichmentService(stores, deals).enrich(Map.of(
                "type", "store", "storePublicId", "store-001", "averageSpendCents", 1
        ));

        assertThat(result).containsEntry("name", "真实门店").containsEntry("averageSpendCents", 6800);
    }

    @Test
    void dealCardUsesSpringDealFieldsAndOwningStore() {
        StoreService stores = mock(StoreService.class);
        DealPackageService deals = mock(DealPackageService.class);
        StoreEntity store = new StoreEntity("store-001", "品牌", "真实门店", "demo", "真实地址", "010", "川菜",
                BigDecimal.ONE, BigDecimal.ONE, 6800, true, "10:00-22:00", null);
        DealPackageEntity deal = new DealPackageEntity("deal-001", store, "真实套餐", "接口说明", 5650, 13500, 9,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(10), true, 1, 0);
        when(deals.require("deal-001")).thenReturn(deal);

        Map<String, Object> result = new AgentCardEnrichmentService(stores, deals).enrich(Map.of(
                "type", "deal", "dealPublicId", "deal-001", "priceCents", 1
        ));

        assertThat(result).containsEntry("title", "真实套餐").containsEntry("priceCents", 5650)
                .containsEntry("storePublicId", "store-001");
    }

    @Test
    void unknownCardTypeIsRejectedWithoutFallbackData() {
        AgentCardEnrichmentService service = new AgentCardEnrichmentService(mock(StoreService.class), mock(DealPackageService.class));

        assertThatThrownBy(() -> service.enrich(Map.of("type", "unknown")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("不支持");
    }
}
