// 团购目录模块：提供门店公开浏览 API。
package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 门店浏览接口；游客也可以读取公开门店数据。 */
@RestController
@RequestMapping("/api/stores")
public class StoreController {
    private final StoreService stores;
    private final DealPackageService deals;
    private final StoreReviewRepository reviews;

    /** 注入门店、套餐和评价查询服务。 */
    public StoreController(StoreService stores, DealPackageService deals, StoreReviewRepository reviews) {
        this.stores = stores; this.deals = deals; this.reviews = reviews;
    }

    /** 按城市、品类、人均和排序方式查询门店。 */
    @GetMapping
    public List<StoreResponse> list(@RequestParam(required = false) String cityCode,
                                    @RequestParam(defaultValue = "RECOMMENDED") String sort,
                                    @RequestParam(required = false) BigDecimal latitude,
                                    @RequestParam(required = false) BigDecimal longitude,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) Integer minPrice,
                                    @RequestParam(required = false) Integer maxPrice) {
        return stores.search(cityCode, sort, latitude, longitude, category, minPrice, maxPrice).stream().map(StoreResponse::from).toList();
    }

    /** 查询门店详情和可售套餐摘要。 */
    @GetMapping("/{publicId}")
    public StoreResponse detail(@PathVariable String publicId) { return StoreResponse.from(stores.require(publicId)); }

    /** 查询门店公开评价，首版返回最新评价摘要。 */
    @GetMapping("/{publicId}/reviews")
    public List<StoreReviewResponse> reviews(@PathVariable String publicId) {
        StoreEntity store = stores.require(publicId);
        return reviews.findAll().stream().filter(item -> item.getStore().getId().equals(store.getId()))
                .sorted(java.util.Comparator.comparing(StoreReviewEntity::getCreatedAt).reversed())
                .limit(20).map(StoreReviewResponse::from).toList();
    }

    /** 查询门店当前可售套餐。 */
    @GetMapping("/{publicId}/deals")
    public List<DealPackageResponse> deals(@PathVariable String publicId, @RequestParam(required = false) String sort) {
        return deals.list(publicId, sort).stream().map(DealPackageResponse::from).toList();
    }
}
