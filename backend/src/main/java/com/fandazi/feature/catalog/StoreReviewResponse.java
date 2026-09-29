// 团购评价模块：定义门店评价公开响应。
package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/** 门店评价公开字段。 */
public record StoreReviewResponse(String storePublicId, String reviewerName, BigDecimal rating, String content, Instant createdAt) {
    /** 将评价实体转换为公开响应。 */
    public static StoreReviewResponse from(StoreReviewEntity review) {
        return new StoreReviewResponse(review.getStore().getPublicId(), review.getReviewerName(), review.getRating(), review.getContent(), review.getCreatedAt());
    }
}
