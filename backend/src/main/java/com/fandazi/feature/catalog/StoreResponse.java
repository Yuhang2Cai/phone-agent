// 团购目录模块：定义门店公开响应，禁止泄露数据库主键。
package com.fandazi.feature.catalog;

import java.math.BigDecimal;

/** 门店公开字段。 */
public record StoreResponse(String publicId, String brandName, String name, String cityCode, String address,
                            String phone, String category, BigDecimal latitude, BigDecimal longitude,
                            int averageSpendCents, boolean open, String openingHours) {
    /** 将持久化实体转换为公开响应。 */
    public static StoreResponse from(StoreEntity store) {
        return new StoreResponse(store.getPublicId(), store.getBrandName(), store.getName(), store.getCityCode(), store.getAddress(),
                store.getPhone(), store.getCategory(), store.getLatitude(), store.getLongitude(), store.getAverageSpendCents(), store.isOpen(), store.getOpeningHours());
    }
}
