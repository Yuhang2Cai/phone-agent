// 缩略图地址测试：只选择同API来源、版本化且无签名查询的已审核派生；未知外部资源保持原地址。
package com.fandazi.app.core.image
import org.junit.Assert.*
import org.junit.Test

/** Task13同时生成full/thumb同名照片，本策略只切换规格，不能选择另一菜品或修改授权查询。 */
class CatalogImageThumbnailUrlTest {
    private val base="http://catalog.fixture:18080/api/"
    private val path="/catalog-images/v1/full/vegetable-rice-de2d10fae70e.jpg"
    /** 站内相对和绝对地址都选择固定派生，重复绑定输出相同键。 */
    @Test fun ownedVersionedFullUsesStableThumbnail() {
        val expected="http://catalog.fixture:18080/catalog-images/v1/thumb/vegetable-rice-de2d10fae70e.jpg"
        assertEquals(expected,CatalogImageUrls.thumbnail(path,base))
        assertEquals(expected,CatalogImageUrls.thumbnail("http://catalog.fixture:18080$path",base))
        assertEquals(expected,CatalogImageUrls.thumbnail(expected,base))
    }
    /** 类似目录路径的外部来源不能按本站约定猜派生；端口和协议也属于来源边界。 */
    @Test fun differentOriginKeepsOriginalResource() {
        for(url in listOf("http://other.fixture:18080$path","http://catalog.fixture:18081$path","https://catalog.fixture:18080$path")) {
            assertEquals(url,CatalogImageUrls.thumbnail(url,base))
        }
    }
    /** 带查询、片段或userinfo的地址可能具有签名和授权含义，完整保留原URL。 */
    @Test fun queryFragmentAndUserInfoAreNotRewritten() {
        for(url in listOf("http://catalog.fixture:18080$path?access=fixture","http://catalog.fixture:18080$path#crop","http://fixture@catalog.fixture:18080$path")) {
            assertEquals(url,CatalogImageUrls.thumbnail(url,base))
        }
    }
    /** 历史外部公开照片仍可展示，禁止把任意full字样当作派生规则。 */
    @Test fun ordinaryLegacyPhotoKeepsUrl() {
        assertEquals("http://catalog.fixture:18080/full/dish.jpg",CatalogImageUrls.thumbnail("http://catalog.fixture:18080/full/dish.jpg",base))
    }
    /** 非法路径和协议继续显示缺图，不绕过既有URL规范。 */
    @Test fun invalidResourceRemainsMissing() {
        assertNull(CatalogImageUrls.thumbnail("file:///dish.jpg",base))
        assertNull(CatalogImageUrls.thumbnail("/catalog-images/v1/full/not-audited.jpg",base))
    }
}
