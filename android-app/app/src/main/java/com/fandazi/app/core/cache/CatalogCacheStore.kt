// 缓存存储边界：纯公开记录与原有序集合，Room适配器随后实现原子读写。
package com.fandazi.app.core.cache
import androidx.room.Entity
/** 一条已经经过公开DTO白名单编码的记录；来源固定在线，不接受用户身份。 */
@Entity(tableName="catalog_records",primaryKeys=["kind","publicId"])
data class CatalogCachedRecord(val kind:String,val publicId:String,val payload:String,val schemaVersion:Int=1,val source:String="ONLINE",val fetchedAt:Long,val expiresAt:Long)
/** 完整查询结果；orderedIds不去重或排序，非法重复在读取时拒绝。 */
@Entity(tableName="catalog_queries",primaryKeys=["queryKey"])
data class CatalogCachedQuery(val queryKey:String,val kind:String,val orderedIds:List<String>,val complete:Boolean=true,val schemaVersion:Int=1,val source:String="ONLINE",val fetchedAt:Long,val expiresAt:Long)
/** 一个事务读取的集合与成员，成员缺失时必须标记不完整，不能借其它查询填充。 */
data class CatalogCachedSnapshot(val query:CatalogCachedQuery,val records:List<CatalogCachedRecord>)
/** 所有方法允许存储异常；上层保证磁盘异常不把在线业务读取改成失败。 */
interface CatalogCacheStore {
    /** 按精确键读集合及其成员，找不到返回null，不查相近条件。 */
    suspend fun read(key:String):CatalogCachedSnapshot?
    /** 原子替换记录及集合，异常不能留下半个查询。 */
    suspend fun save(query:CatalogCachedQuery,records:List<CatalogCachedRecord>)
}
/** 明确类型的编解码边界，实际运行只装配公开目录DTO编码器。 */
interface CatalogCacheCodec<T> {
    /** 只编码允许的公开字段并返回稳定公开ID与载荷。 */
    fun encode(value:T):List<Pair<String,String>>
    /** 按原集合顺序解码并校验身份，非法载荷抛出格式异常。 */
    fun decode(rows:List<Pair<String,String>>):T
}
