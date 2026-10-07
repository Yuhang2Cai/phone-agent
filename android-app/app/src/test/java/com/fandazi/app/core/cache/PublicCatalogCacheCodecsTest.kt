// 白名单序列化测试：证明精确位置/账户载荷无法进入公开缓存，实体身份与有序集合一致。
package com.fandazi.app.core.cache
import com.fandazi.app.feature.store.CatalogStore
import org.junit.Assert.*
import org.junit.Test
class PublicCatalogCacheCodecsTest {
    private val id="00000000-0000-4000-8000-000000000001"
    /** 合成公开门店含一次定位距离，仅测试应被裁剪的输出，无真实用户信息。 */
    private fun store()=CatalogStore(id,"测试店","110000","火锅",1000,"公开地址","10:00",null,0,emptyList(),123L,null,null,null)
    /** DTO中的定位派生字段也不能落盘，离线重读的距离必须明确未知。 */
    @Test fun locationDerivedFieldNotPersisted(){val encoded=PublicCatalogCacheCodecs.stores.encode(listOf(store()));assertFalse("距离不能落盘",encoded.single().second.contains("distanceMeters"));assertNull(PublicCatalogCacheCodecs.stores.decode(encoded).single().distanceMeters)}
    /** 旧库/外来JSON夹带账号字段必须拒绝，不能让Gson静默忽略后当合法快照。 */
    @Test fun protectedFieldRejected(){val raw=PublicCatalogCacheCodecs.stores.encode(listOf(store())).single().second.dropLast(1)+",\"accountId\":\"synthetic-private\"}";try{PublicCatalogCacheCodecs.stores.decode(listOf(id to raw));fail("受保护字段必须拒绝")}catch(expected:IllegalArgumentException){assertTrue(expected.message.orEmpty().contains("公开"))}}
    /** 集合键ID与载荷ID不一致不能借缓存另一资源显示。 */
    @Test fun wrongPayloadIdentityRejected(){val encoded=PublicCatalogCacheCodecs.stores.encode(listOf(store()));try{PublicCatalogCacheCodecs.stores.decode(listOf("00000000-0000-4000-8000-000000000002" to encoded.single().second));fail("公开身份必须一致")}catch(expected:IllegalArgumentException){assertTrue(expected.message.orEmpty().contains("身份"))}}
}
