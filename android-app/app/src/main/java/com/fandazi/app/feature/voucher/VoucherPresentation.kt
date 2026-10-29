// 订单与券显示模块：将后端枚举及整数分格式化为中文，依赖公开模型，不授予业务资格。
package com.fandazi.app.feature.voucher

import com.fandazi.app.core.time.BusinessDisplay

/** 列表分段只映射后端订单状态；取消、过期、待支付留在全部，不能冒充已使用或退款。 */
enum class OrderSegment { ALL, AVAILABLE, COMPLETED, REFUNDED }

/** 纯显示转换，无网络及持久化副作用；未知状态显示缺值，不根据日期推定状态。 */
object VoucherPresentation {
    /** 输入后端订单状态，返回中文标签；只支持实际协议枚举。 */
    fun orderStatus(status: String): String = when (status) {
        "PENDING_PAYMENT" -> "待支付"
        "PAID" -> "待使用"
        "PARTIALLY_USED" -> "部分使用"
        "USED" -> "已完成"
        "REFUNDED" -> "已退款"
        "EXPIRED" -> "已过期"
        "CANCELLED" -> "已取消"
        "CLOSED_TIMEOUT" -> "超时关闭"
        else -> "状态未取得"
    }

    /** 输入后端券状态，返回中文标签；未付款信息另用订单事实展示，不将 UNUSED 等同已付款。 */
    fun voucherStatus(status: String): String = when (status) {
        "UNUSED" -> "待使用"
        "REDEEMED" -> "已使用"
        "REFUNDED" -> "已退款"
        "EXPIRED" -> "已过期"
        // 秒杀权益状态只解释服务器事实，不能把待支付或未来使用日期显示为待使用。
        "PENDING_PAYMENT" -> "待支付"
        "CLOSED_TIMEOUT" -> "超时关闭"
        "NOT_YET_VALID" -> "未到使用日期"
        "UNAVAILABLE" -> "暂不可用"
        else -> "状态未取得"
    }

    /** 根据后端实际状态选出分段；不以 canRefund=false 推测已申请售后。 */
    fun matches(order: GroupOrderSummary, segment: OrderSegment): Boolean = when (segment) {
        OrderSegment.ALL -> true
        OrderSegment.AVAILABLE -> order.status in setOf("PAID", "PARTIALLY_USED")
        OrderSegment.COMPLETED -> order.status == "USED"
        OrderSegment.REFUNDED -> order.status == "REFUNDED"
    }

    /** 实付只能使用明确付款快照；待支付标待支付，其他无付款事实的终态显示合计。 */
    fun amount(order: GroupOrderSummary): String = when {
        order.paidTotalCents != null -> "实付 ${BusinessDisplay.money(order.paidTotalCents)}"
        order.status == "PENDING_PAYMENT" -> "待支付 ${BusinessDisplay.money(order.totalCents)}"
        else -> "合计 ${BusinessDisplay.money(order.totalCents)}"
    }
}
