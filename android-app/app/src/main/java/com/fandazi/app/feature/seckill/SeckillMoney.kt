// 秒杀展示金额：沿用后端整数分，仅统一确认稿要求的两位小数，不参与交易金额计算。
package com.fandazi.app.feature.seckill

/** 输入后端金额分，返回带人民币符号的两位小数文本；不会改变报价或订单内容。 */
fun seckillMoney(cents:Int):String="¥"+java.math.BigDecimal.valueOf(cents.toLong(),2).toPlainString()
