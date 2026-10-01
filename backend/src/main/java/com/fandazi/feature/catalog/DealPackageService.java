// 团购目录模块：提供可售套餐的公开查询。
package com.fandazi.feature.catalog;

import com.fandazi.feature.DomainException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 固定团购套餐查询服务。 */
@Service
public class DealPackageService {
    private final DealPackageRepository deals;
    private final StoreService stores;

    /** 注入套餐仓储和门店校验服务。 */
    public DealPackageService(DealPackageRepository deals, StoreService stores) { this.deals = deals; this.stores = stores; }

    /** 查询门店当前有效且仍有库存的套餐。 */
    @Transactional(readOnly = true)
    public List<DealPackageEntity> list(String storePublicId, String sort) {
        StoreEntity store = stores.require(storePublicId);
        Comparator<DealPackageEntity> comparator = "PRICE_ASC".equalsIgnoreCase(sort)
                ? Comparator.comparingInt(DealPackageEntity::getPriceCents).thenComparing(DealPackageEntity::getPublicId)
                : Comparator.comparing(DealPackageEntity::getPublicId);
        LocalDate today = LocalDate.now();
        return deals.findAll().stream()
                .filter(deal -> deal.getStore().getId().equals(store.getId()))
                .filter(deal -> deal.isActive() && deal.getInventory() > 0 && !today.isBefore(deal.getValidFrom()) && !today.isAfter(deal.getValidTo()))
                .sorted(comparator)
                .toList();
    }

    /** 按套餐公开标识查询有效套餐。 */
    @Transactional(readOnly = true)
    public DealPackageEntity require(String publicId) {
        DealPackageEntity deal = deals.findByPublicId(publicId).orElseThrow(() -> new DomainException("套餐不存在"));
        if (!deal.isActive() || deal.getInventory() <= 0) throw new DomainException("套餐暂不可购买");
        return deal;
    }

    /** 在只读事务内构造套餐页面响应，门店公开 ID 和图片读取完成后才离开持久化会话；不存在时抛出领域异常。 */
    @Transactional(readOnly = true)
    public DealPackageResponse detail(String publicId) {
        // require 返回的关联可能是懒加载代理；页面 DTO 必须在此读取真实门店信息，避免 Controller 中触发无会话异常。
        return DealPackageResponse.from(require(publicId));
    }
}
