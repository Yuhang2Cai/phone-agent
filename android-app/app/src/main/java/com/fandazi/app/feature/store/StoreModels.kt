package com.fandazi.app.feature.store

/** 门店浏览页只使用后端返回的公开字段。 */
data class StoreSummary(
    val publicId: String,
    val name: String,
    val category: String,
    val averageSpendCents: Int,
    val address: String,
    val openingHours: String
)

/** 固定团购套餐摘要。 */
data class DealSummary(
    val publicId: String,
    val title: String,
    val priceCents: Int,
    val originalPriceCents: Int,
    val inventory: Int
)
