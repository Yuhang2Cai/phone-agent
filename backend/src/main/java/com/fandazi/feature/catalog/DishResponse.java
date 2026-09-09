package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.util.List;

public record DishResponse(Long id, Long merchantId, String category, String name, String description,
                           BigDecimal price, int stock, boolean listed, String imageUrl, List<Option> options,
                           BigDecimal rating, String spiciness, List<String> ingredients, List<String> allergens, BigDecimal previousPrice, boolean priceChanged) {
    public record Option(Long id, String groupName, String optionName, BigDecimal priceDelta, boolean available) {
        static Option from(DishOptionEntity option) {
            return new Option(option.getId(), option.getGroupName(), option.getOptionName(),
                    option.getPriceDelta(), option.isAvailable());
        }
    }

    public static DishResponse from(DishEntity dish, List<DishOptionEntity> options) {
        return new DishResponse(dish.getId(), dish.getMerchant().getId(), dish.getCategory(), dish.getName(),
                dish.getDescription(), dish.getPrice(), dish.getStock(), dish.isListed(), dish.getImageUrl(),
                options.stream().map(Option::from).toList(), null, null, List.of(), List.of(), null, false);
    }
    public static DishResponse from(DishEntity dish, List<DishOptionEntity> options, com.fandazi.seed.SeedDishMetadataEntity metadata) {
        DishResponse base=from(dish, options);
        return new DishResponse(base.id(),base.merchantId(),base.category(),base.name(),base.description(),base.price(),base.stock(),base.listed(),base.imageUrl(),base.options(),metadata.getRating(),metadata.getSpiciness(),metadata.getIngredients(),metadata.getAllergens(),metadata.getPreviousPrice(),metadata.isPriceChanged());
    }
}
