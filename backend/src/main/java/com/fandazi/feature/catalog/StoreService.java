// 团购目录模块：提供门店筛选、稳定排序和公开详情查询。
package com.fandazi.feature.catalog;

import com.fandazi.feature.DomainException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 门店查询服务，所有排序都在 Spring 侧基于真实字段完成。 */
@Service
public class StoreService {
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
        String normalizedSort = sort == null || sort.isBlank() ? "RECOMMENDED" : sort.toUpperCase();
        if ("DISTANCE".equals(normalizedSort) && !usableLocation(latitude, longitude)) {
            throw new DomainException("VALIDATION_ERROR:距离排序需要有效的经纬度");
        }
        if (!List.of("RECOMMENDED", "PRICE_ASC", "PRICE_DESC", "DISTANCE").contains(normalizedSort)) {
            throw new DomainException("VALIDATION_ERROR:不支持的门店排序方式");
        }
        List<StoreEntity> result = stores.findAll().stream()
                .filter(store -> cityCode == null || cityCode.isBlank() || cityCode.equals(store.getCityCode()))
                .filter(store -> category == null || category.isBlank() || category.equals(store.getCategory()))
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

    private boolean usableLocation(BigDecimal latitude, BigDecimal longitude) {
        return latitude != null && longitude != null
                && latitude.compareTo(BigDecimal.valueOf(-90)) >= 0 && latitude.compareTo(BigDecimal.valueOf(90)) <= 0
                && longitude.compareTo(BigDecimal.valueOf(-180)) >= 0 && longitude.compareTo(BigDecimal.valueOf(180)) <= 0;
    }

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
    private double distance(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double earth = 6371.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double value = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earth * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }
}
