// 帮助中心服务：集中维护团购售后与评价规则，供页面和 Agent 共用。
package com.fandazi.feature.support;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 只回答已实现的本地平台规则，不推断具体订单的退款结果。 */
@Service
public class FaqService {
    /** 规则文案与服务资格判断一致；具体状态仍须查询用户自己的券或订单。 */
    private static final List<Map<String, String>> ITEMS = List.of(
            // 购买和用券帮助只描述当前已实现的模拟支付与券信息查询；核销暂缓，不承诺可到店核销。
            Map.of("id", "purchase-flow", "question", "如何购买团购？",
                    "answer", "选择套餐后确认订单并完成模拟支付；支付成功后可在订单中查看团购券。"),
            Map.of("id", "voucher-use", "question", "如何使用团购券？",
                    "answer", "可在我的订单中查看团购券的有效期和使用规则；到店核销功能目前暂缓开放。"),
            Map.of("id", "refund-eligibility", "question", "什么情况下可以申请退款？",
                    "answer", "未核销且未重复申请的团购券可以提交退款申请；申请提交后请在退款进度中查看处理状态。"),
            // 游客不能使用 AI 助手；帮助内容不要求客户端猜测账号能力或维护本地副本。
            Map.of("id", "account-login", "question", "账号与登录如何使用？",
                    "answer", "使用账号和密码登录后，可以查看订单和使用 AI 助手；游客点击 AI 助手需先登录。"),
            // 平台规则明确区分已上线的演示支付和暂缓的核销，防止帮助页给出超出服务能力的承诺。
            Map.of("id", "platform-rules", "question", "平台规则有哪些？",
                    "answer", "平台当前提供团购套餐浏览、模拟支付、订单与团购券查询；到店核销功能暂缓开放，退款申请结果以进度页实际状态为准。"),
            Map.of("id", "review-eligibility", "question", "什么时候可以评价门店？",
                    "answer", "订单中至少一张团购券核销后可以评价，每笔订单只能评价一次。"),
            Map.of("id", "coupon-use", "question", "如何查看优惠券？",
                    "answer", "请在我的优惠券中查看已领取优惠券的状态、门槛、有效期和适用范围。"),
            Map.of("id", "complaint", "question", "如何反馈门店问题？",
                    "answer", "可在帮助与客服中提交投诉；关联订单时请选择您自己的团购订单。"));

    /** 按问题文本过滤已知条目；空关键词返回全部公开规则。 */
    public List<Map<String, String>> query(String keyword) {
        String text = keyword == null ? "" : keyword.trim();
        return ITEMS.stream().filter(item -> text.isEmpty() || item.get("question").contains(text)
                || item.get("answer").contains(text)).toList();
    }
}
