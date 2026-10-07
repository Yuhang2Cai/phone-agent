// 团购券模块：定义 Android 与后端团购订单、团购券接口之间的传输模型。
package com.fandazi.app.feature.voucher

/** 只读券页恢复已有退款申请所需的公开身份；不包含准备凭证或退款写入方法。 */
data class VoucherRefundLink(val requestId: String, val voucherId: String)

/** 后端返回的单张团购券公开摘要；不包含数据库二维码密钥。 */
data class VoucherSummary(
    val publicId: String,
    val orderId: String,
    val dealId: String,
    val dealTitle: String,
    val storeId: String,
    val storeName: String,
    val status: String,
    val expiresAt: String?,
    val redeemedAt: String?,
    // 退款资格由后端结合支付、券状态和历史申请计算，端上不能只按 UNUSED 推断。
    val canRefund: Boolean = false,
    // 金额是支付时保存的单券分摊快照；未支付为 null，图片允许缺失而不补虚构餐图。
    val amountCents: Int? = null,
    val createdAt: String? = null,
    val imageUrl: String? = null,
    val imageAlt: String? = null,
    // 页面专用关联由后端查本人订单提供；工具旧摘要可缺这些字段，不能在端上猜测。
    val orderQuantity: Int? = null,
    val orderStatus: String? = null,
    val orderCreatedAt: String? = null,
    val dealDescription: String? = null
)

/** 后端返回的团购订单摘要，列表和详情共用。 */
data class GroupOrderSummary(
    val orderId: String,
    val dealId: String,
    val storeId: String,
    val quantity: Int,
    val totalCents: Int,
    val status: String,
    val voucherCount: Int,
    val vouchers: List<VoucherSummary>,
    // 评价资格由后端确认是否已有核销券以及是否已发表过评价。
    val canReview: Boolean = false,
    // 下单时间是服务端订单事实；评价页只能展示该值，不能用设备当前时间替代。
    val createdAt: String? = null,
    // 无券订单同样有自己的真实摘要，不能靠首张券推断门店或套餐。
    val storeName: String = "",
    val dealTitle: String = "",
    val imageUrl: String? = null,
    val imageAlt: String? = null,
    // 实付与时间直接取持久化快照；取消或过期状态不代表一定支付过。
    val paidTotalCents: Int? = null,
    val paidAt: String? = null,
    val expiresAt: String? = null,
    val dealDescription: String? = null
)

/** 按需生成的短时二维码展示令牌。 */
data class VoucherDisplayToken(val voucherId: String, val token: String, val expiresAt: String?)
