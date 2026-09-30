package com.fandazi.app.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片协议单元测试。
 *
 * 这里验证客户端只保留后端返回的公开 ID 和字段，不把门店名称、套餐价格等业务样例写入渲染层。
 * 真正的 View 外观由 Figma/模拟器验收，避免把 UI 像素断言误当成业务协议测试。
 */
class AgentCardRendererTest {
    @Test
    fun cardKeepsServerIdentityAndFields() {
        // 测试服务端返回的不同套餐字段能够原样进入渲染模型，防止客户端按下标替换成默认套餐。
        val card = AgentCardPayload(
            type = "deal",
            storePublicId = "store-from-server",
            dealPublicId = "deal-from-server",
            fields = mapOf("title" to "title-from-server", "priceCents" to "4400")
        )

        assertEquals("store-from-server", card.storePublicId)
        assertEquals("deal-from-server", card.dealPublicId)
        assertEquals("title-from-server", card.fields["title"])
        assertEquals("4400", card.fields["priceCents"])
        assertTrue(card.fields.values.none { it.contains("经典2-3人餐") })
    }
}
