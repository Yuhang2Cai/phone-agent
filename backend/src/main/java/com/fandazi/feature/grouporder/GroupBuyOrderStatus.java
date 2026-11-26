// 团购订单模块：定义订单从创建到使用和售后的有限状态机。
package com.fandazi.feature.grouporder;

/** 团购订单状态；状态名称同时写入数据库和跨服务事件。 */
public enum GroupBuyOrderStatus {
    PENDING_PAYMENT,
    PAID,
    PARTIALLY_USED,
    USED,
    REFUNDED,
    EXPIRED,
    CANCELLED,
    // 秒杀未付款在独立支付截止关闭；不能与券自然到期EXPIRED混为一谈。
    CLOSED_TIMEOUT
}
