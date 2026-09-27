package com.fandazi.feature.catalog;

import com.fandazi.seed.SeedDishMetadataRepository;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final SeedDishMetadataRepository metadata;
    private final MerchantReviewRepository reviews;

    public MerchantController(MerchantRepository merchants, DishRepository dishes, DishOptionRepository options, SeedDishMetadataRepository metadata, MerchantReviewRepository reviews) {
        this.merchants = merchants;
        this.dishes = dishes;
        this.options = options;
        this.metadata = metadata;
        this.reviews = reviews;
    }

    @GetMapping
    public Page<MerchantResponse> search(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) Integer maxDeliveryMinutes,
                                         @RequestParam(required = false) BigDecimal maxPrice,
                                         @RequestParam(defaultValue = "false") boolean openOnly,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
        var result = merchants.findAll(pageable.getSort()).stream()
                .filter(merchant -> matches(merchant, keyword, category, maxDeliveryMinutes, maxPrice, openOnly))
                .map(MerchantResponse::from)
                .toList();
        int from = Math.min((int) pageable.getOffset(), result.size());
        int to = Math.min(from + pageable.getPageSize(), result.size());
        return new PageImpl<>(result.subList(from, to), pageable, result.size());
    }

    private boolean matches(MerchantEntity merchant, String keyword, String category, Integer minutes, BigDecimal maxPrice, boolean openOnly) {
        boolean merchantOrDishMatches = keyword == null
                || merchant.getName().contains(keyword)
                || dishes.findByMerchantId(merchant.getId()).stream().anyMatch(dish ->
                dish.getName().contains(keyword) || dish.getDescription().contains(keyword));
        return merchantOrDishMatches
                && (category == null || merchant.getCategory().equals(category))
                && (minutes == null || merchant.getDeliveryMinutes() <= minutes)
                && (maxPrice == null || merchant.getMinimumOrder().compareTo(maxPrice) <= 0)
                && (!openOnly || merchant.isOpen());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MerchantResponse> merchant(@PathVariable Long id) {
        return merchants.findById(id)
                .map(MerchantResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{merchantId}/reviews")
    public ResponseEntity<Page<MerchantReviewResponse>> reviews(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        if (!merchants.existsById(merchantId)) return ResponseEntity.notFound().build();
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return ResponseEntity.ok(reviews.findByMerchantId(merchantId, pageable).map(MerchantReviewResponse::from));
    }

    @GetMapping("/{id}/dishes")
    public List<DishResponse> dishes(@PathVariable Long id) {
        return dishes.findByMerchantId(id).stream()
                .map(dish -> metadata.findByDishId(dish.getId()).map(m -> DishResponse.from(dish, options.findByDishId(dish.getId()), m)).orElseGet(() -> DishResponse.from(dish, options.findByDishId(dish.getId()))))
                .toList();
    }
}
