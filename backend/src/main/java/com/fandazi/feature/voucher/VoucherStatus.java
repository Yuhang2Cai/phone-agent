// 团购券模块：定义单张团购券的使用和退款状态。
package com.fandazi.feature.voucher;

/** 团购券状态；只有 UNUSED 可以进入退款申请。 */
public enum VoucherStatus {
    UNUSED,
    REDEEMED,
    REFUNDED,
    EXPIRED
}
