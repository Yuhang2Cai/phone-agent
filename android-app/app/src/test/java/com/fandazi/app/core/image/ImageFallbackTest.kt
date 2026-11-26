// 图片加载模块：验证关键行为与边界条件。
package com.fandazi.app.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFallbackTest {
    @Test
// 验证 `failedImageLoadKeepsTextContentAvailable` 覆盖场景的预期行为与边界。
    fun failedImageLoadKeepsTextContentAvailable() {
        val content = ImageContentFallback.render("招牌牛肉饭", "https://example.invalid/dish.png") { false }

        assertEquals("招牌牛肉饭", content.title)
        assertNull(content.loadedImageUrl)
    }
}
