// 团购评价 HTTP 入口：仅从登录态获取评价人身份。
package com.fandazi.feature.review;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 将订单公开 ID、评分和内容提交给领域服务做资格校验。 */
@RestController
@RequestMapping("/api/group-reviews")
public class ReviewController {
    /** 客户端只传评价内容，不能自报用户和门店。 */
    public record CreateRequest(@NotBlank String orderId, int rating, @NotBlank String content) {}
    private final ReviewService reviews;

    /** 注入评价服务。 */
    public ReviewController(ReviewService reviews) { this.reviews = reviews; }

    /** 提交评价并返回真实评分与订单关联。 */
    @PostMapping
    public Map<String, Object> create(@AuthenticationPrincipal Long userId, @RequestBody CreateRequest request) {
        return reviews.create(userId, request.orderId(), request.rating(), request.content());
    }
}
