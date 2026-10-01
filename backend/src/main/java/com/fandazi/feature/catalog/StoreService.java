// 团购目录模块：提供门店筛选、稳定排序和公开详情查询。
package com.fandazi.feature.catalog;

import com.fandazi.feature.DomainException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 门店查询服务，所有排序都在 Spring 侧基于真实字段完成。 */
@Service
public class StoreService {
    // 首页审核后的分组映射只影响目录查询，不参与 Agent 意图分类；原始品类查询仍保持精确匹配。
    private static final Map<String, List<String>> CATEGORY_GROUPS = Map.of(
            "奶茶咖啡", List.of("饮品", "奶茶", "咖啡"),
            "快餐小吃", List.of("盖饭", "快餐", "粥汤", "轻食", "小吃", "面食", "面点", "汤饭"),
            "火锅", List.of("火锅"), "烧烤烤肉", List.of("烧烤", "烤肉"),
            "地方菜系", List.of("川菜", "家常菜", "粤菜", "湘菜", "本帮菜"));
    private final StoreRepository stores;

    /** 注入门店仓储。 */
    public StoreService(StoreRepository stores) { this.stores = stores; }

    /**
     * 查询门店并执行确定性排序。
     * 距离排序必须同时提供有效经纬度，避免把缺少定位的结果伪装成附近推荐。
     */
    @Transactional(readOnly = true)
    public List<StoreEntity> search(String cityCode, String sort, BigDecimal latitude, BigDecimal longitude,
                                    String category, Integer minPrice, Integer maxPrice) {
        // 输入校验：负值和倒置范围是客户端参数错误，不能返回貌似成功的未筛选结果。
        if ((minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)
                || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
            throw new DomainException("VALIDATION_ERROR:人均筛选范围无效");
        }
        String normalizedSort = sort == null || sort.isBlank() ? "RECOMMENDED" : sort.toUpperCase(Locale.ROOT);
        if ("DISTANCE".equals(normalizedSort) && !usableLocation(latitude, longitude)) {
            throw new DomainException("VALIDATION_ERROR:距离排序需要有效的经纬度");
        }
        if (!List.of("RECOMMENDED", "PRICE_ASC", "PRICE_DESC", "DISTANCE").contains(normalizedSort)) {
            throw new DomainException("VALIDATION_ERROR:不支持的门店排序方式");
        }
        // 数据规范化与规则判断：分组标签由服务端展开，全部条件使用 AND，排序在真实实体集合上执行。
        List<String> categories = category == null || category.isBlank() || "推荐".equals(category)
                ? List.of() : CATEGORY_GROUPS.getOrDefault(category, List.of(category));
        List<StoreEntity> result = stores.findAll().stream()
                .filter(store -> cityCode == null || cityCode.isBlank() || cityCode.equals(store.getCityCode()))
                .filter(store -> categories.isEmpty() || categories.contains(store.getCategory()))
                .filter(store -> minPrice == null || store.getAverageSpendCents() >= minPrice)
                .filter(store -> maxPrice == null || store.getAverageSpendCents() <= maxPrice)
                .filter(StoreEntity::isOpen)
                .sorted(comparator(normalizedSort, latitude, longitude))
                .toList();
        return result;
    }

    /** 按公开标识查询门店，不向上层暴露数据库自增 ID。 */
    @Transactional(readOnly = true)
    public StoreEntity require(String publicId) {
        return stores.findByPublicId(publicId).orElseThrow(() -> new DomainException("门店不存在"));
    }

    /** 核对完整坐标对与地理范围；未知位置不能产生精确距离。 */
    public boolean usableLocation(BigDecimal latitude, BigDecimal longitude) {
        return latitude != null && longitude != null
                && latitude.compareTo(BigDecimal.valueOf(-90)) >= 0 && latitude.compareTo(BigDecimal.valueOf(90)) <= 0
                && longitude.compareTo(BigDecimal.valueOf(-180)) >= 0 && longitude.compareTo(BigDecimal.valueOf(180)) <= 0;
    }

    /** 用公开 UUID 稳定打破同价/同距离平局，避免连续查询发生顺序漂移。 */
    private Comparator<StoreEntity> comparator(String sort, BigDecimal latitude, BigDecimal longitude) {
        Comparator<StoreEntity> stable = Comparator.comparing(StoreEntity::getPublicId);
        return switch (sort) {
            case "PRICE_ASC" -> Comparator.comparingInt(StoreEntity::getAverageSpendCents).thenComparing(stable);
            case "PRICE_DESC" -> Comparator.comparingInt(StoreEntity::getAverageSpendCents).reversed().thenComparing(stable);
            case "DISTANCE" -> Comparator.<StoreEntity>comparingDouble(store -> distance(latitude, longitude, store.getLatitude(), store.getLongitude())).thenComparing(stable);
            default -> stable;
        };
    }

    /** 使用 Haversine 计算公里数；排序只需要相对距离，因此结果不写入业务数据。 */
    public double distance(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double earth = 6371.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double value = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        // 浮点三角运算可能略超出 [0,1]；夹紧仅修正计算误差，不修改输入坐标。
        value = Math.max(0, Math.min(1, value));
        return earth * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }
}
