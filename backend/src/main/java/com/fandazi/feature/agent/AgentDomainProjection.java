// 工具领域投影模块：从真实实体/API 摘要构造公开资源字段，集中转换旧 LocalDateTime 为 UTC 协议时间。
package com.fandazi.feature.agent;

import com.fandazi.feature.catalog.*;
import java.time.*;
import java.util.*;

/** 无状态投影不生成虚构资源、价格或资格；缺少必需事实由出口 schema 明确报错。 */
final class AgentDomainProjection {
    /** 历史领域 LocalDateTime 按数据库明确的上海业务时区解释，不能直接追加 Z 改变原时间含义。 */
    private static final ZoneId DOMAIN_ZONE = ZoneId.of("Asia/Shanghai");
    /** 禁止实例化，所有投影共享同一套金额与时间语义。 */
    private AgentDomainProjection() {}
    /** 门店全部必需字段来自实体，公开 ID 替代数据库主键，金额保留整数分。 */
    static Map<String, Object> store(StoreEntity store) {
        var result = new LinkedHashMap<String, Object>();
        result.put("publicId", store.getPublicId()); result.put("name", store.getName()); result.put("brandName", store.getBrandName());
        result.put("cityCode", store.getCityCode()); result.put("address", store.getAddress()); result.put("category", store.getCategory());
        result.put("averageSpendCents", store.getAverageSpendCents()); result.put("latitude", store.getLatitude()); result.put("longitude", store.getLongitude());
        result.put("open", store.isOpen()); result.put("openingHours", store.getOpeningHours()); result.put("phone", store.getPhone());
        return result;
    }
    /** 套餐有效期保持 LocalDate；业务日期不能伪造为某个 UTC 时间点。 */
    static Map<String, Object> deal(DealPackageEntity deal) {
        var result = new LinkedHashMap<String, Object>();
        result.put("publicId", deal.getPublicId()); result.put("storeId", deal.getStore().getPublicId()); result.put("title", deal.getTitle());
        result.put("description", deal.getDescription()); result.put("priceCents", deal.getPriceCents()); result.put("originalPriceCents", deal.getOriginalPriceCents());
        result.put("inventory", deal.getInventory()); result.put("validFrom", deal.getValidFrom().toString()); result.put("validTo", deal.getValidTo().toString());
        result.put("purchaseLimit", deal.getPurchaseLimit()); result.put("active", deal.isActive());
        // 工具和普通目录 API 使用同一套餐真实图片，缺失时原样输出 null。
        result.put("imageUrl", deal.getImageUrl()); result.put("imageAlt", deal.getImageAlt());
        return result;
    }
    /** 公开评价直接使用数据库存储的 Instant 和评分，禁止拼造点评内容。 */
    static Map<String, Object> review(StoreReviewEntity review) {
        return Map.of("storeId", review.getStore().getPublicId(), "reviewerName", review.getReviewerName(), "rating", review.getRating(),
                "content", review.getContent(), "createdAt", review.getCreatedAt().toString());
    }
    /** 优惠券领域有效日期为含首尾日的业务范围，转换为真实 UTC 起止边界供时间点契约消费。 */
    static Map<String, Object> coupon(Map<String, Object> source) {
        var result = new LinkedHashMap<>(source);
        result.put("validFrom", LocalDate.parse((String) source.get("validFrom")).atStartOfDay(DOMAIN_ZONE).toInstant().toString());
        result.put("validTo", LocalDate.parse((String) source.get("validTo")).plusDays(1).atStartOfDay(DOMAIN_ZONE).toInstant().minusNanos(1).toString());
        return result;
    }
    /** 将领域摘要递归转换为可校验 JSON；所有券和订单的时间使用相同转换规则，合法空值保持 null。 */
    static Map<String, Object> times(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (value instanceof String text && Set.of("createdAt", "expiresAt", "redeemedAt", "validFrom", "validTo").contains(key) && text.contains("T")) {
                result.put(key, utc(text));
            } else if (value instanceof Map<?, ?> nested) {
                var object = new LinkedHashMap<String, Object>(); nested.forEach((name, item) -> object.put(String.valueOf(name), item));
                result.put(key, times(object));
            } else if (value instanceof List<?> list) {
                result.put(key, list.stream().map(item -> {
                    if (!(item instanceof Map<?, ?> nested)) return item;
                    var object = new LinkedHashMap<String, Object>(); nested.forEach((name, field) -> object.put(String.valueOf(name), field));
                    return times(object);
                }).toList());
            } else result.put(key, value);
        });
        return result;
    }
    /** 先按已有偏移转换；只有历史无时区时间才按业务时区解释，解析失败由控制器安全报错。 */
    private static String utc(String value) {
        try { return OffsetDateTime.parse(value).toInstant().toString(); }
        catch (java.time.format.DateTimeParseException exception) { return LocalDateTime.parse(value).atZone(DOMAIN_ZONE).toInstant().toString(); }
    }
}
