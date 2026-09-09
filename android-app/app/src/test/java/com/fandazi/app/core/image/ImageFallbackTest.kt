package com.fandazi.app.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFallbackTest {
    @Test
    fun failedImageLoadKeepsTextContentAvailable() {
        val content = ImageContentFallback.render("招牌牛肉饭", "https://example.invalid/dish.png") { false }

        assertEquals("招牌牛肉饭", content.title)
        assertNull(content.loadedImageUrl)
    }
}
