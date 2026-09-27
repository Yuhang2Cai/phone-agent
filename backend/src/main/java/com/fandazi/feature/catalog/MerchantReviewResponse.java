package com.fandazi.feature.catalog;

import java.math.BigDecimal;
import java.time.Instant;

public record MerchantReviewResponse(
        Long id, Long merchantId, String reviewerName,
        BigDecimal rating, String content, Instant createdAt
) {
    static MerchantReviewResponse from(MerchantReviewEntity value) {
        return new MerchantReviewResponse(value.getId(), value.getMerchant().getId(),
                value.getReviewerName(), value.getRating(), value.getContent(), value.getCreatedAt());
    }
}
