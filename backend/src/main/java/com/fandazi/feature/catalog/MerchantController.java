package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/merchants")
@Validated
public class MerchantController {
    private final MerchantRepository merchants;
    private final DishRepository dishes;
    private final DishOptionRepository options;

    public MerchantController(MerchantRepository merchants, DishRepository dishes, DishOptionRepository options) {
        this.merchants = merchants;
        this.dishes = dishes;
        this.options = options;
    }

    @GetMapping
    public Page<MerchantResponse> search(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) Integer maxDeliveryMinutes,
                                         @RequestParam(required = false) BigDecimal maxPrice,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Pageable pageable = PageRequest.of(page, size);
        var result = merchants.findAll().stream()
                .filter(merchant -> matches(merchant, keyword, category, maxDeliveryMinutes, maxPrice))
                .map(MerchantResponse::from)
                .toList();
        int from = Math.min((int) pageable.getOffset(), result.size());
        int to = Math.min(from + pageable.getPageSize(), result.size());
        return new PageImpl<>(result.subList(from, to), pageable, result.size());
    }

    private boolean matches(MerchantEntity merchant, String keyword, String category, Integer minutes, BigDecimal maxPrice) {
        return (keyword == null || merchant.getName().contains(keyword))
                && (category == null || merchant.getCategory().equals(category))
                && (minutes == null || merchant.getDeliveryMinutes() <= minutes)
                && (maxPrice == null || merchant.getMinimumOrder().compareTo(maxPrice) <= 0);
    }

    @GetMapping("/{id}/dishes")
    public List<DishResponse> dishes(@PathVariable Long id) {
        return dishes.findByMerchantId(id).stream()
                .map(dish -> DishResponse.from(dish, options.findByDishId(dish.getId())))
                .toList();
    }
}
