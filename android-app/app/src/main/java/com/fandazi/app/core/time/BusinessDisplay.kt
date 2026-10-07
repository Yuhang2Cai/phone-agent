// 业务展示格式模块：供订单、券与售后页面把后端整数分和 UTC 时间转换为用户可读文本。
package com.fandazi.app.core.time

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 后端是金额和时间的唯一事实来源；此模块只格式化，不推断业务状态或设备时区。 */
object BusinessDisplay {
    /** 固定采用业务约定的上海时区，避免模拟器系统时区改变订单日期。 */
    private val zone = ZoneId.of("Asia/Shanghai")
    private val minuteFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 输入整数分或未取得的金额；精确转为两位小数，null 不冒充零元。 */
    fun money(cents: Int?): String = cents?.let { "¥${BigDecimal.valueOf(it.toLong(), 2).toPlainString()}" }
        ?: "金额未取得"

    /** 输入带时区 ISO 时间；非法或缺失值明确展示不可用，禁止截字符串造成 UTC 误显示。 */
    fun time(iso: String?): String = iso?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(minuteFormat) }.getOrNull()
    } ?: "时间未取得"
}
