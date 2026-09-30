package com.fandazi.app.feature.deal

/**
 * 团购套餐公开字段，与后端 `DealPackageResponse` 保持一一对应。
 * 金额使用分保存，只有进入展示层时才转换成人民币字符串，避免浮点误差。
 */
data class DealPackage(
    val publicId: String,
    val storePublicId: String,
    val title: String,
    val description: String,
    val priceCents: Int,
    val originalPriceCents: Int,
    val inventory: Int,
    val validFrom: String,
    val validTo: String,
    val active: Boolean,
    val purchaseLimit: Int,
) {
    /** 将分转换为页面展示金额，统一保留两位以内的小数。 */
    fun priceText(): String = formatCents(priceCents)

    /** 将原价转换为页面展示金额。 */
    fun originalPriceText(): String = formatCents(originalPriceCents)

    /** 统一金额格式，避免通过浮点数参与价格计算。 */
    private fun formatCents(cents: Int): String {
        val yuan = cents / 100
        val fen = cents % 100
        return if (fen == 0) "¥$yuan" else "¥$yuan.${fen.toString().padStart(2, '0').trimEnd('0')}"
    }
}
