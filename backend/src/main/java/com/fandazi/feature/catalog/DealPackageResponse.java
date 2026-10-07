// 团购目录模块：定义固定套餐公开响应。
package com.fandazi.feature.catalog;

import java.time.LocalDate;

/** 套餐公开字段。 */
public record DealPackageResponse(String publicId, String storePublicId, String title, String description,
                                  int priceCents, int originalPriceCents, int inventory, LocalDate validFrom,
                                  LocalDate validTo, boolean active, int purchaseLimit, String imageUrl, String imageAlt,
                                  Integer peopleCount, java.util.List<DealContent> contents, String usageRules, String storeName) {
    /** 原客户端/测试仍可构造旧字段，新资料未知；不凭标题补人数。 */
    public DealPackageResponse(String publicId,String storePublicId,String title,String description,int priceCents,int originalPriceCents,int inventory,LocalDate validFrom,LocalDate validTo,boolean active,int purchaseLimit,String imageUrl,String imageAlt){
        this(publicId,storePublicId,title,description,priceCents,originalPriceCents,inventory,validFrom,validTo,active,purchaseLimit,imageUrl,imageAlt,null,null,null,null);
    }
    /** 将套餐实体与真实门店关联投影为公开响应，调用方必须在只读事务内读取。 */
    public static DealPackageResponse from(DealPackageEntity deal) {
        return new DealPackageResponse(deal.getPublicId(), deal.getStore().getPublicId(), deal.getTitle(), deal.getDescription(),
                deal.getPriceCents(), deal.getOriginalPriceCents(), deal.getInventory(), deal.getValidFrom(), deal.getValidTo(), deal.isActive(), deal.getPurchaseLimit(),
                deal.getImageUrl(), deal.getImageAlt(),deal.getPeopleCount(),deal.getContents(),deal.getUsageRules(),deal.getStore().getName());
    }
}
