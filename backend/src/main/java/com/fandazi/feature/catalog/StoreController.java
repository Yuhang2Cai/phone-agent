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
    private final StoreBrowseService browse;

    /** 注入门店、套餐和评价查询服务。 */
    public StoreController(StoreService stores, DealPackageService deals, StoreReviewRepository reviews, StoreBrowseService browse) {
        this.stores = stores; this.deals = deals; this.reviews = reviews; this.browse = browse;
    }

    /** 按城市、品类、人均和排序方式查询门店。 */
    @GetMapping
    public List<StoreBrowseResponse> list(@RequestParam(required = false) String cityCode,
                                    @RequestParam(defaultValue = "RECOMMENDED") String sort,
                                    @RequestParam(required = false) BigDecimal latitude,
                                    @RequestParam(required = false) BigDecimal longitude,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) Integer minPrice,
                                    @RequestParam(required = false) Integer maxPrice,
                                    @RequestParam(required = false) String keyword) {
        // 所有参数分别传入服务端查询；响应摘要与顺序都由服务器权威计算。
        return browse.search(cityCode, sort, latitude, longitude, category, minPrice, maxPrice, keyword);
    }

    /** 查询门店详情和可售套餐摘要。 */
    @GetMapping("/{publicId}")
    public StoreBrowseResponse detail(@PathVariable String publicId,
            @RequestParam(required = false) BigDecimal latitude, @RequestParam(required = false) BigDecimal longitude) {
        return browse.detail(publicId, latitude, longitude);
    }

    /** 读取当前真实目录的城市选项；游客无需登录，不提供虚构城市。 */
    @GetMapping("/cities")
    public List<StoreBrowseService.City> cities() { return browse.cities(); }

    /** 门店菜品仅供公开浏览；无关联数据时返回空列表，不回退到固定菜单。 */
    @GetMapping("/{publicId}/dishes")
    public List<StoreBrowseService.Dish> dishes(@PathVariable String publicId) { return browse.dishes(publicId); }

    /** 查询门店公开评价，首版返回最新评价摘要。 */
    @GetMapping("/{publicId}/reviews")
    public List<StoreReviewResponse> reviews(@PathVariable String publicId) {
        // 关联门店公开 ID 的读取必须留在服务事务中，不能依赖已关闭的会话。
        return browse.reviews(publicId);
    }

    /** 查询门店当前可售套餐。 */
    @GetMapping("/{publicId}/deals")
    public List<DealPackageResponse> deals(@PathVariable String publicId, @RequestParam(required = false) String sort) {
        return deals.list(publicId, sort).stream().map(DealPackageResponse::from).toList();
    }
}
