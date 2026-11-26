// 公开浏览响应模块：为 UI-01 至 UI-05 汇总真实目录事实，依赖套餐响应和服务器评价统计。
package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 门店完整展示字段；rating/imageUrl/featuredDeal 可空，表示缺少事实，不构造样例兜底。
 * distanceMeters 仅在请求带有效位置时存在；reviewDistribution 按一星至五星排列。
 * 保留原门店接口字段，新增摘要不会改变 17 工具的既有投影。
 */
public record StoreBrowseResponse(String publicId, String brandName, String name, String cityCode,
        String address, String phone, String category, BigDecimal latitude, BigDecimal longitude,
        int averageSpendCents, boolean open, String openingHours, BigDecimal rating, int reviewCount,
        List<Integer> reviewDistribution, Long distanceMeters, String imageUrl, String imageAlt,
        DealPackageResponse featuredDeal) {}
