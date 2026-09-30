// Agent 卡片模块：校验模型返回的公开资源标识，并补充 Spring 侧的真实业务字段。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.DealPackageService;
import com.fandazi.feature.catalog.StoreEntity;
import com.fandazi.feature.catalog.StoreService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 将 Agent 只读的卡片引用转换为可展示的业务卡片。
 *
 * <p>模型只能提交公开 ID，不能直接提交价格、库存或地址。这里重新从 Spring 领域服务读取数据，
 * 防止客户端展示过期值或越权资源；任何未知卡片类型和缺失 ID 都直接失败，不返回静态兜底数据。</p>
 */
@Service
public class AgentCardEnrichmentService {
    private final StoreService stores;
    private final DealPackageService deals;

    /** 注入门店和套餐领域服务，确保卡片数据遵循统一权限与有效性规则。 */
    public AgentCardEnrichmentService(StoreService stores, DealPackageService deals) {
        this.stores = stores;
        this.deals = deals;
    }

    /**
     * 校验并补全一张卡片。
     *
     * @param reference Agent 返回的卡片引用，至少包含 `type` 和对应公开 ID
     * @return 只包含客户端可展示字段的结构化卡片
     * @throws DomainException 卡片类型未知、公开 ID 缺失或资源不可用时抛出
     */
    public Map<String, Object> enrich(Map<String, Object> reference) {
        String type = text(reference, "type");
        return switch (type) {
            case "store" -> enrichStore(required(reference, "storePublicId"));
            case "deal" -> enrichDeal(required(reference, "dealPublicId"));
            default -> throw new DomainException("VALIDATION_ERROR:不支持的 Agent 卡片类型");
        };
    }

    /** 读取门店真实字段，金额仍以整数分传输，避免客户端浮点误差。 */
    private Map<String, Object> enrichStore(String publicId) {
        StoreEntity store = stores.require(publicId);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "store");
        card.put("storePublicId", store.getPublicId());
        card.put("name", store.getName());
        card.put("address", store.getAddress());
        card.put("category", store.getCategory());
        card.put("averageSpendCents", store.getAverageSpendCents());
        card.put("openingHours", store.getOpeningHours());
        card.put("open", store.isOpen());
        return card;
    }

    /** 读取套餐真实字段，并同时返回所属门店公开 ID用于受控跳转。 */
    private Map<String, Object> enrichDeal(String publicId) {
        DealPackageEntity deal = deals.require(publicId);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "deal");
        card.put("dealPublicId", deal.getPublicId());
        card.put("storePublicId", deal.getStore().getPublicId());
        card.put("title", deal.getTitle());
        card.put("description", deal.getDescription());
        card.put("priceCents", deal.getPriceCents());
        card.put("originalPriceCents", deal.getOriginalPriceCents());
        card.put("inventory", deal.getInventory());
        card.put("validFrom", deal.getValidFrom().toString());
        card.put("validTo", deal.getValidTo().toString());
        card.put("purchaseLimit", deal.getPurchaseLimit());
        return card;
    }

    /** 公开 ID 必须是非空字符串，避免把 null 或任意对象拼成资源地址。 */
    private String required(Map<String, Object> reference, String field) {
        String value = text(reference, field);
        if (value.isBlank()) throw new DomainException("VALIDATION_ERROR:Agent 卡片缺少" + field);
        return value;
    }

    private String text(Map<String, Object> values, String field) {
        Object value = values == null ? null : values.get(field);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
