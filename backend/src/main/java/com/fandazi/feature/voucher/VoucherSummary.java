// 券页面投影：订单内嵌券与独立券接口复用同一事实，依赖券实体及统一时间边界。
package com.fandazi.feature.voucher;

import com.fandazi.core.time.BusinessTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 不包含内部主键和二维码令牌；资格由调用者已按本人资源计算，金额只读支付分摊快照。 */
public final class VoucherSummary {
    private VoucherSummary() {}

    /** 输入本人券和服务端资格，返回可供订单页、详情页与工具消费的公开摘要，允许合法 null。 */
    public static Map<String, Object> from(VoucherEntity voucher, boolean canRefund) {
        var order = voucher.getOrder();
        var deal = voucher.getDeal();
        Map<String, Object> data = new LinkedHashMap<>();
        // 资源关系从持久化关联读取，客户端不能自报门店、套餐或订单归属。
        data.put("publicId", voucher.getPublicId()); data.put("orderId", order.getPublicId());
        data.put("dealId", deal.getPublicId()); data.put("dealTitle", deal.getTitle());
        data.put("storeId", order.getStore().getPublicId()); data.put("storeName", order.getStore().getName());
        var now=BusinessTime.now();
        data.put("status", voucher.publicStatusAt(now)); data.put("canRefund", canRefund);
        if(order.getSeckillActivity()!=null){
            // 未付款/已关闭券不能只因底层UNUSED显示“可使用”；日期取券快照，不读活动当前配置。
            data.put("canUse",voucher.canUseAt(now));
            data.put("validFrom",voucher.getValidFrom()==null?null:voucher.getValidFrom().toString());
        }
        // 未付款没有可退金额；图片缺失保持 null，不能借用别的套餐图。
        data.put("amountCents", voucher.getAmountCents());
        data.put("imageUrl", deal.getImageUrl()); data.put("imageAlt", deal.getImageAlt());
        data.put("createdAt", BusinessTime.utc(voucher.getCreatedAt()));
        data.put("expiresAt", BusinessTime.utc(voucher.getExpiresAt()));
        data.put("redeemedAt", BusinessTime.utc(voucher.getRedeemedAt()));
        return data;
    }
}
