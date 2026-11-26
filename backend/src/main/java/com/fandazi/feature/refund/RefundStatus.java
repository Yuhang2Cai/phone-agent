// 售后模块：定义退款申请的处理状态。
package com.fandazi.feature.refund;

/** 退款申请状态。 */
public enum RefundStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    COMPLETED
}
