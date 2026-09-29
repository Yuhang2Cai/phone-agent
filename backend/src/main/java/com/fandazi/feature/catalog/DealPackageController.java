// 团购目录模块：提供套餐详情 API。
package com.fandazi.feature.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 固定套餐公开详情接口。 */
@RestController
@RequestMapping("/api/deals")
public class DealPackageController {
    private final DealPackageService deals;

    /** 注入套餐查询服务。 */
    public DealPackageController(DealPackageService deals) { this.deals = deals; }

    /** 查询套餐价格、库存、有效期和使用说明。 */
    @GetMapping("/{publicId}")
    public DealPackageResponse detail(@PathVariable String publicId) { return DealPackageResponse.from(deals.require(publicId)); }
}
