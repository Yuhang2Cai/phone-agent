// 真实浏览模块：在只读事务中组装目录、菜品、城市及评价摘要；依赖现有门店/套餐/菜单仓储。
package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 页面聚合服务，所有 DTO 在事务内完成，避免关联懒加载跨出会话导致真实浏览失败。 */
@Service
@Transactional(readOnly = true)
public class StoreBrowseService {
    private final StoreService stores;
    private final StoreRepository storeRepository;
    private final DealPackageService deals;
    private final StoreReviewRepository reviews;
    private final DishRepository dishes;

    /** 注入权威目录及关联仓储；本服务没有写数据库的职责。 */
    public StoreBrowseService(StoreService stores, StoreRepository storeRepository, DealPackageService deals,
            StoreReviewRepository reviews, DishRepository dishes) {
        this.stores = stores; this.storeRepository = storeRepository; this.deals = deals;
        this.reviews = reviews; this.dishes = dishes;
    }

    /** 查询条件独立表达；搜索覆盖真实门店、可售套餐和关联菜单，结果保留后端排序顺序。 */
    public List<StoreBrowseResponse> search(String city, String sort, BigDecimal latitude, BigDecimal longitude,
            String category, Integer minPrice, Integer maxPrice, String keyword) {
        // 数据规范化：只修剪关键词首尾空白，不能把排序文字拼进业务关键词。
        String term = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var candidates = stores.search(city, sort, latitude, longitude, category, minPrice, maxPrice).stream()
                .filter(store -> matches(store, term)).toList();
        if (candidates.isEmpty()) return List.of();
        // 一次读取候选门店的评价，再在内存按门店分组；不存在 N 次全表扫描或评价关联懒加载。
        Map<Long, List<StoreReviewEntity>> grouped = reviews.findByStoreIdIn(candidates.stream().map(StoreEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(review -> review.getStore().getId()));
        return candidates.stream().map(store -> summary(store, latitude, longitude, grouped.getOrDefault(store.getId(), List.of()))).toList();
    }

    /** 获取完整门店详情；非法公开 ID 由原目录服务拒绝，不使用默认门店。 */
    public StoreBrowseResponse detail(String publicId, BigDecimal latitude, BigDecimal longitude) {
        StoreEntity store = stores.require(publicId);
        return summary(store, latitude, longitude, reviews.findByStoreId(store.getId()));
    }

    /** 城市选项只来自现有营业门店；目前目录仅存代码，显示代码而不臆测它对应的真实城市。 */
    public List<City> cities() {
        return storeRepository.findAll().stream().filter(StoreEntity::isOpen).map(StoreEntity::getCityCode)
                .distinct().sorted().map(code -> new City(code, code)).toList();
    }

    /** 按门店关联的真实菜单读取展示数据；无关联或无菜品时返回空数组，金额精确转换为整数分。 */
    public List<Dish> dishes(String publicId) {
        StoreEntity store = stores.require(publicId);
        if (store.getMerchant() == null) return List.of();
        return dishes.findByMerchantId(store.getMerchant().getId()).stream().filter(DishEntity::isListed)
                .map(dish -> new Dish(dish.getName(), dish.getDescription(),
                        dish.getPrice().movePointRight(2).intValueExact(), dish.getImageUrl(), dish.getPublicId())).toList();
    }

    /** 在只读事务内完成评价 DTO 转换；离开事务后不再访问懒加载的关联门店。 */
    public List<StoreReviewResponse> reviews(String publicId) {
        StoreEntity store = stores.require(publicId);
        return reviews.findByStoreId(store.getId()).stream()
                .sorted(java.util.Comparator.comparing(StoreReviewEntity::getCreatedAt).reversed())
                .limit(20).map(StoreReviewResponse::from).toList();
    }

    /** 仅做目录文本匹配，不参与意图分类；菜单关联缺失时只检查门店和真实套餐。 */
    private boolean matches(StoreEntity store, String term) {
        if (term.isEmpty()) return true;
        if (contains(store.getName(), term) || contains(store.getBrandName(), term) || contains(store.getCategory(), term)) return true;
        if (deals.list(store.getPublicId(), null).stream()
                .anyMatch(deal -> contains(deal.getTitle(), term) || contains(deal.getDescription(), term))) return true;
        return store.getMerchant() != null && dishes.findByMerchantId(store.getMerchant().getId()).stream()
                .filter(DishEntity::isListed).anyMatch(dish -> contains(dish.getName(), term));
    }

    /** 空字段不会命中关键词，英文文本按固定语言环境折叠大小写。 */
    private boolean contains(String value, String term) { return value != null && value.toLowerCase(Locale.ROOT).contains(term); }

    /** 汇总全量评价和当前可售套餐；缺图沿用 null，未知定位沿用 null，不编造评分或距离。 */
    private StoreBrowseResponse summary(StoreEntity store, BigDecimal latitude, BigDecimal longitude, List<StoreReviewEntity> all) {
        // 规则判断：摘要统计全量真实评价，不能把只返回最近 20 条的列表数量误当总评价数。
        List<Integer> distribution = new ArrayList<>(List.of(0, 0, 0, 0, 0));
        for (var review : all) {
            int bucket = Math.max(1, Math.min(5, review.getRating().setScale(0, RoundingMode.HALF_UP).intValue())) - 1;
            distribution.set(bucket, distribution.get(bucket) + 1);
        }
        BigDecimal rating = all.isEmpty() ? null : all.stream().map(StoreReviewEntity::getRating)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(all.size()), 1, RoundingMode.HALF_UP);
        var packages = deals.list(store.getPublicId(), "PRICE_ASC");
        DealPackageResponse featured = packages.isEmpty() ? null : DealPackageResponse.from(packages.get(0));
        Long distance = stores.usableLocation(latitude, longitude)
                ? Math.round(stores.distance(latitude, longitude, store.getLatitude(), store.getLongitude()) * 1000) : null;
        // 结果构造：门店没有独立封面字段，使用本店摘要套餐的真实图片并保留其替代文本。
        return new StoreBrowseResponse(store.getPublicId(), store.getBrandName(), store.getName(), store.getCityCode(),
                store.getAddress(), store.getPhone(), store.getCategory(), store.getLatitude(), store.getLongitude(),
                store.getAverageSpendCents(), store.isOpen(), store.getOpeningHours(), rating, all.size(), distribution,
                distance, featured == null ? null : featured.imageUrl(), featured == null ? null : featured.imageAlt(), featured);
    }

    /** 城市展示选项，代码与名称均由实际目录构造，不保存客户端城市常量。 */
    public record City(String cityCode, String name) {}
    /** 只读菜品展示字段，不暴露旧外卖数值主键、库存写入或加购能力。 */
    public record Dish(String name, String description, int priceCents, String imageUrl, String publicId) {}
}
