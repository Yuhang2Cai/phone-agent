// 团购目录模块：定义固定套餐公开响应。
package com.fandazi.feature.catalog;

import java.time.LocalDate;

/** 套餐公开字段。 */
public record DealPackageResponse(String publicId, String storePublicId, String title, String description,
                                  int priceCents, int originalPriceCents, int inventory, LocalDate validFrom,
                                  LocalDate validTo, boolean active, int purchaseLimit, String imageUrl, String imageAlt) {
    /** 将套餐实体转换为公开响应。 */
    public static DealPackageResponse from(DealPackageEntity deal) {
        return new DealPackageResponse(deal.getPublicId(), deal.getStore().getPublicId(), deal.getTitle(), deal.getDescription(),
                deal.getPriceCents(), deal.getOriginalPriceCents(), deal.getInventory(), deal.getValidFrom(), deal.getValidTo(), deal.isActive(), deal.getPurchaseLimit(),
                deal.getImageUrl(), deal.getImageAlt());
    }
}
