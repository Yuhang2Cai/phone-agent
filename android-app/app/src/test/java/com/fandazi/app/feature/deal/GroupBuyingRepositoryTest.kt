package com.fandazi.app.feature.deal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 验证团购套餐接口响应能够被解析成不同的套餐对象。
 * 该测试防止详情页重新退回“所有套餐共用固定标题和价格”的回归。
 */
class GroupBuyingRepositoryTest {

    @Test
    fun parseListPreservesEachDealIdentityAndPrice() {
        // 使用两个不同套餐模拟后端列表，要求公开 ID、标题和金额全部原样保留。
        val body = """
            [
              {"publicId":"deal-001-1","storePublicId":"store-001","title":"经典2-3人餐","description":"含香辣巴骨肉","priceCents":5650,"originalPriceCents":13500,"inventory":10,"validFrom":"2026-01-01","validTo":"2026-12-31","active":true,"purchaseLimit":1},
              {"publicId":"deal-001-2","storePublicId":"store-001","title":"双人餐","description":"含双人特色菜","priceCents":4400,"originalPriceCents":6400,"inventory":8,"validFrom":"2026-01-01","validTo":"2026-12-31","active":true,"purchaseLimit":2}
            ]
        """.trimIndent()

        val deals = DealPackagePayloadParser.parseList(body)

        assertEquals(2, deals.size)
        assertEquals("deal-001-1", deals[0].publicId)
        assertEquals("经典2-3人餐", deals[0].title)
        assertEquals(5650, deals[0].priceCents)
        assertEquals("deal-001-2", deals[1].publicId)
        assertEquals("双人餐", deals[1].title)
        assertEquals(4400, deals[1].priceCents)
    }

    @Test
    fun parseDetailReturnsTheRequestedDealInsteadOfDefaultingToFirstItem() {
        // 详情接口只返回当前公开 ID 对应的一个套餐，不能根据固定下标或默认标题替代它。
        val body = """
            {"publicId":"deal-001-2","storePublicId":"store-001","title":"双人餐","description":"含双人特色菜","priceCents":4400,"originalPriceCents":6400,"inventory":8,"validFrom":"2026-01-01","validTo":"2026-12-31","active":true,"purchaseLimit":2}
        """.trimIndent()

        val deal = DealPackagePayloadParser.parseDetail(body)

        assertNotNull(deal)
        assertEquals("deal-001-2", deal.publicId)
        assertEquals("双人餐", deal.title)
        assertEquals(4400, deal.priceCents)
    }
}
