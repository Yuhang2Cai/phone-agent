// 团购评价服务：以实际券核销记录判定资格，并限制每单仅能评价一次。
package com.fandazi.feature.review;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.catalog.StoreReviewEntity;
import com.fandazi.feature.catalog.StoreReviewRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import com.fandazi.feature.voucher.VoucherStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户评价写入门店评价表，但归属、次数和资格按团购订单校验。 */
@Service
public class ReviewService {
    private final GroupBuyOrderRepository orders;
    private final StoreReviewRepository reviews;

    /** 注入订单和评价仓储。 */
    public ReviewService(GroupBuyOrderRepository orders, StoreReviewRepository reviews) {
        this.orders = orders; this.reviews = reviews;
    }

    /** 核销过至少一张券的订单可以评价；唯一约束兜住并发重复请求。 */
    @Transactional
    public Map<String, Object> create(Long userId, String orderId, int rating, String content) {
        var order = orders.findByUserIdAndPublicId(userId, orderId)
                .orElseThrow(() -> new DomainException("团购订单不存在"));
        if (order.getVouchers().stream().noneMatch(voucher -> voucher.getStatus() == VoucherStatus.REDEEMED)) {
            throw new DomainException("核销后才能评价");
        }
        if (rating < 1 || rating > 5) throw new DomainException("评分必须为1至5分");
        if (content == null || content.isBlank() || content.length() > 1000) throw new DomainException("评价内容长度不合法");
        if (reviews.existsByOrderId(order.getId())) throw new DomainException("该订单已评价");
        // 评价只保存门店和订单引用；用户名从用户实体读取，不接受客户端自报昵称。
        var review = reviews.save(new StoreReviewEntity(order.getStore(), order.getId(), order.getUser().getUsername(),
                BigDecimal.valueOf(rating), content.trim(), Instant.now()));
        return Map.of("orderId", order.getPublicId(), "storeId", order.getStore().getPublicId(),
                "rating", review.getRating(), "content", review.getContent());
    }
}
