// 套餐对比展示边界：仅消费已验证公开事实，整数分总价保持；人均使用明确人数与十进制舍入。
package com.fandazi.app.feature.compare
import com.fandazi.app.feature.deal.DealPackage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
/** 不持久化的只读事实，人数默认来自同一权威套餐；第三参数仅供明确调用者传真实可空人数。 */
data class CompareDealFacts(val deal:DealPackage,val storeName:String,val peopleCount:Int?=deal.peopleCount) {
    /** 人数未知/非正数返回未提供；总价分除以人数，四舍五入至整数分再固定显示2位元。 */
    fun personPriceText():String {
        val count=peopleCount?.takeIf{it>0} ?: return "未提供"
        val cents=BigDecimal.valueOf(deal.priceCents.toLong()).divide(BigDecimal.valueOf(count.toLong()),0,RoundingMode.HALF_UP)
        return "¥"+cents.movePointLeft(2).setScale(2).toPlainString()+"/人"
    }
    /** 只作保守导航门槛，购买回详情后仍须新事实/报价/确认，快照不授权交易。 */
    fun purchaseAvailable():Boolean {
        val today=LocalDate.now()
        return deal.active && deal.inventory>0 && !today.isBefore(LocalDate.parse(deal.validFrom)) && !today.isAfter(LocalDate.parse(deal.validTo))
    }
}
