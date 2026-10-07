// 公开缓存策略测试：固定时间和真实查询条件，防止越界快照、位置落盘及跨条件借用。
package com.fandazi.app.core.cache
import com.fandazi.app.feature.store.CatalogQuery
import org.junit.Assert.*
import org.junit.Test
class CatalogCachePolicyTest {
    /** 固定墙钟避免依赖执行时间；精确测试15分钟及24小时的等号方向。 */
    private val now=100_000_000L
    /** 边界前1毫秒仍新鲜，到15分钟即过期。 */
    @Test fun freshnessBoundary(){assertEquals(CatalogCacheAge.FRESH,CatalogCachePolicy.age(now-899_999,now));assertEquals(CatalogCacheAge.STALE,CatalogCachePolicy.age(now-900_000,now))}
    /** 24小时整仍只读；多1毫秒不得再展示价格库存。 */
    @Test fun expirationBoundary(){assertEquals(CatalogCacheAge.STALE,CatalogCachePolicy.age(now-86_400_000,now));assertEquals(CatalogCacheAge.EXPIRED,CatalogCachePolicy.age(now-86_400_001,now))}
    /** 设备时间倒退不能伪装成新鲜，拒绝未来时间记录。 */
    @Test fun clockRollback(){assertEquals(CatalogCacheAge.INVALID,CatalogCachePolicy.age(now+1,now))}
    /** 关键词、城市、分类、排序每项都参与键，防止另一个查询填充当前列表。 */
    @Test fun fullQueryIsolation(){val q=CatalogQuery(cityCode="110000");val key=CatalogCachePolicy.queryKey("stores",q);assertNotNull(key);listOf(q.copy(cityCode="310000"),q.copy(category="火锅"),q.copy(keyword="双人"),q.copy(sort="PRICE_ASC")).forEach{assertNotEquals(key,CatalogCachePolicy.queryKey("stores",it))}}
    /** 不同分隔符组合不可产生键碰撞，外围空白按HTTP实际规范化。 */
    @Test fun keyEncoding(){assertNotEquals(CatalogCachePolicy.queryKey("stores",CatalogQuery(keyword="a|b",category="c")),CatalogCachePolicy.queryKey("stores",CatalogQuery(keyword="a",category="b|c")));assertEquals(CatalogCachePolicy.queryKey("stores",CatalogQuery(keyword=" a ")),CatalogCachePolicy.queryKey("stores",CatalogQuery(keyword="a")))}
    /** 精确坐标和距离排序只在线读取；不能生成落盘键或借无定位缓存。 */
    @Test fun noPreciseLocation(){assertNull(CatalogCachePolicy.queryKey("stores",CatalogQuery(latitude=39.9)));assertNull(CatalogCachePolicy.queryKey("stores",CatalogQuery(longitude=116.4)));assertNull(CatalogCachePolicy.queryKey("stores",CatalogQuery(sort="DISTANCE")))}
    /** 模式、来源及expiresAt是一起校验的缓存契约，旧schema不被解码。 */
    @Test fun schemaAndExpiry(){assertTrue(CatalogCachePolicy.valid(1,"ONLINE",now,now+86_400_000,now));assertFalse(CatalogCachePolicy.valid(2,"ONLINE",now,now+86_400_000,now));assertFalse(CatalogCachePolicy.valid(1,"PRIVATE",now,now+86_400_000,now));assertFalse(CatalogCachePolicy.valid(1,"ONLINE",now,now+1,now))}
}
