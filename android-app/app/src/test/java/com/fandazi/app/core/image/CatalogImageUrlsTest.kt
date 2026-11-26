// 目录餐图地址测试模块：验证模拟器与部署主机解析及非法路径拒绝。
package com.fandazi.app.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 保障后端相对图片路径不会被 Glide 误认为设备本地路径。 */
class CatalogImageUrlsTest {
    /** API 基址包含 /api/ 时，资源仍必须从站点根路径加载。 */
    @Test fun resolvesVersionedHostedImageFromCurrentBackend() {
        val path = "/catalog-images/v1/full/chili-chicken-rice-5ec12eff9907.jpg"
        assertEquals("http://10.0.2.2:18080$path", CatalogImageUrls.resolve(path,
            "http://10.0.2.2:18080/api/"))
    }

    /** 历史 HTTP 图片保持原值，以免旧订单和券无法查看当时快照。 */
    @Test fun keepsExistingHttpsImageUrl() {
        assertEquals("https://example.org/order.jpg", CatalogImageUrls.resolve(
            "https://example.org/order.jpg", "http://10.0.2.2:18080/api/"))
    }

    /** 不审核的站内路径与本地文件协议必须拒绝，避免错误加载设备文件。 */
    @Test fun rejectsUnreviewedPathsAndLocalFiles() {
        assertNull(CatalogImageUrls.resolve("/private/order.jpg", "http://10.0.2.2:18080/api/"))
        assertNull(CatalogImageUrls.resolve("file:///sdcard/food.jpg", "http://10.0.2.2:18080/api/"))
        assertNull(CatalogImageUrls.resolve("/catalog-images/v1/full/../private.jpg",
            "http://10.0.2.2:18080/api/"))
    }
}
