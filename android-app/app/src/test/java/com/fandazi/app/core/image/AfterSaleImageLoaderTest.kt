// 售后图片真实加载测试：通过Glide与本地HTTP服务器验证缺图、实际404、原URL点击重试，不伪造业务图片。
package com.fandazi.app.core.image

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.widget.ImageView
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** 使用Android运行时和真实HTTP响应，防止橙色空块、失败缓存或换图掩盖加载错误。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AfterSaleImageLoaderTest {
    /** 无URL保持准确缺图说明；点击只触发宿主元数据读取，不请求无关图片。 */
    @Test fun missingUrlShowsExplicitStateAndRefreshesMetadata() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val image = ImageView(controller.get())
        controller.get().setContentView(image)
        var refreshes = 0
        AfterSaleImageLoader.load(image, null, "接口套餐标题") { refreshes++ }
        assertEquals("暂无图片，点击刷新", image.contentDescription)
        assertNotNull(image.drawable)
        image.performClick()
        assertEquals(1, refreshes)
        controller.pause().stop().destroy()
    }

    /** 404是真实加载失败；用户点击后同路径重新请求并显示实际响应图片，成功后移除重试。 */
    @Test fun failedHttpImageRetriesSameUrlAndThenShowsOriginalAlt() {
        val server = MockWebServer()
        server.start()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val image = ImageView(controller.get())
            controller.get().setContentView(image)
            image.layout(0, 0, 160, 160)
            server.enqueue(MockResponse().setResponseCode(404))
            val url = server.url("/original-dish.png").toString()
            AfterSaleImageLoader.load(image, url, "接口原图说明") { fail("有URL失败不能刷新其他业务资源") }
            waitUntil { image.contentDescription == "图片加载失败，点击重试" }
            assertEquals("/original-dish.png", server.takeRequest().path)
            // 仅单测HTTP图片使用内存生成的小PNG，不进入业务接口或生产资源。
            val bytes = ByteArrayOutputStream().also {
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
            image.performClick()
            waitUntil { image.contentDescription == "接口原图说明" }
            assertEquals("/original-dish.png", server.takeRequest().path)
            assertEquals(2, server.requestCount)
            assertFalse(image.isClickable)
            // 后续元数据变成null时必须清掉旧图片，不能让上一张原图冒充新资源。
            val previous = image.drawable
            AfterSaleImageLoader.load(image, null, null) { }
            assertNotSame(previous, image.drawable)
            assertEquals("暂无图片，点击刷新", image.contentDescription)
        } finally {
            controller.pause().stop().destroy()
            server.shutdown()
        }
    }

    /** Glide在后台读取真实HTTP；有界泵送主线程回调，超时保留失败而不是无限等待。 */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("未取得预期的真实图片加载状态", condition())
    }
}
