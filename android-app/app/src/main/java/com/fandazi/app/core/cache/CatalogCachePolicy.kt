// 公开缓存纯策略：固定完整查询键与时效，不接受账号或位置作为持久字段。
package com.fandazi.app.core.cache
import com.fandazi.app.feature.store.CatalogQuery
/** 年龄状态对应新鲜、过期只读、隐藏和无效，设备时间倒退不能变成新鲜。 */
enum class CatalogCacheAge { FRESH, STALE, EXPIRED, INVALID }
/** 无Android依赖的冻结策略，数据库和仓库共用，避免各页面自行猜时间。 */
object CatalogCachePolicy {
    /** 缓存结构版本，仅用于纯公开库，不是订单或业务数据库版本。 */
    const val SCHEMA_VERSION=1
    /** 不足15分钟新鲜；到达本边界进入过期只读。 */
    const val FRESH_MILLIS=900_000L
    /** 24小时整允许过期只读；多1毫秒隐藏。 */
    const val MAX_MILLIS=86_400_000L
    /** 输入成功获取时间和墙钟；负数/未来时间拒绝，防溢出先比较再求差。 */
    fun age(fetchedAt:Long,now:Long):CatalogCacheAge {
        if(fetchedAt<0 || now<fetchedAt)return CatalogCacheAge.INVALID
        val elapsed=now-fetchedAt
        return when {elapsed<FRESH_MILLIS->CatalogCacheAge.FRESH;elapsed<=MAX_MILLIS->CatalogCacheAge.STALE;else->CatalogCacheAge.EXPIRED}
    }
    /** 与HTTP实际规范化一致，任一坐标或距离排序不生成持久键，也不借无定位集合。 */
    fun queryKey(endpoint:String,query:CatalogQuery):String? {
        if(query.latitude!=null || query.longitude!=null || query.sort=="DISTANCE")return null
        return key(endpoint,query.cityCode?.takeUnless{it.isBlank()}.orEmpty(),query.category.takeUnless{it=="推荐"}.orEmpty(),query.keyword.trim(),query.sort)
    }
    /** 长度前缀消除分隔符碰撞；端点类别与原资源ID必须由调用方显式传入。 */
    fun key(vararg parts:String):String=parts.joinToString("|"){"${it.length}:$it"}
    /** 同时验证结构、来源和约定时效；过期由age单列，以便页面给出超过24小时说明。 */
    fun valid(version:Int,source:String,fetchedAt:Long,expiresAt:Long,now:Long):Boolean=
        version==SCHEMA_VERSION && source=="ONLINE" && fetchedAt>=0 && fetchedAt<=Long.MAX_VALUE-MAX_MILLIS && expiresAt==fetchedAt+MAX_MILLIS && age(fetchedAt,now)!=CatalogCacheAge.INVALID
}
