// 图片策略测试：固定资源版本键和访问归属，防止每次绑定随机失效或私人资源串账户。
package com.fandazi.app.core.image

import org.junit.Assert.*
import org.junit.Test

/** 纯逻辑只验证稳定身份与隔离约束；两级缓存行为另由真实Glide设备测试证明。 */
class CatalogImageRequestPolicyTest {
    /** 无服务器版本时仍复用URL身份，重复调用不得产生随机签名。 */
    @Test fun ordinaryVersionIdentityIsStableAndRetryKeepsResourceIdentity() {
        val normal=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions())
        val again=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions())
        assertEquals(normal.signature,again.signature)
        assertTrue(normal.cacheEnabled)
        val retry=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(mode=CatalogImageRequestMode.RETRY))
        assertEquals("重试只绕缓存，不换资源身份",normal.signature,retry.signature)
        assertFalse(retry.cacheEnabled)
    }
    /** 稳定URL版本改变才换键；前后空白规范化不制造额外缓存副本。 */
    @Test fun serverVersionChangesIdentityWithoutTimestamp() {
        val v1=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(resourceVersion=" revision-1 "))
        val same=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(resourceVersion="revision-1"))
        val v2=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(resourceVersion="revision-2"))
        assertEquals(v1.signature,same.signature)
        assertNotEquals(v1.signature,v2.signature)
    }
    /** 私人访问不读写公共缓存，账户身份还隔离正在加载的同URL请求。 */
    @Test fun protectedResourcesCannotSharePublicOrOtherAccountIdentity() {
        val public=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions())
        val first=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(access=CatalogImageAccess.Private("fixture-a")))
        val second=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(access=CatalogImageAccess.Private("fixture-b")))
        assertFalse(first.cacheEnabled);assertFalse(second.cacheEnabled)
        assertNotEquals(public.signature,first.signature);assertNotEquals(first.signature,second.signature)
    }
    /** 缺少账户身份时拒绝私人请求，不能退回共享缓存归属。 */
    @Test(expected=IllegalArgumentException::class) fun protectedResourceRequiresAccountIdentity() {
        CatalogImageAccess.Private(" ")
    }
}
