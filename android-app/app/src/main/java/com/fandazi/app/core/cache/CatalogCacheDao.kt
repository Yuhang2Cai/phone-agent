// 公开缓存DAO：只操作两张纯公开表；事务读防止查询集合与成员跨版本拼接。
package com.fandazi.app.core.cache
import androidx.room.*
/** Room生成实际SQLite实现，SQL不包含账号、受保护响应或业务表。 */
@Dao abstract class CatalogCacheDao {
    /** 精确主键读取集合，找不到返回null，不尝试相近查询。 */
    @Query("SELECT * FROM catalog_queries WHERE queryKey=:key") abstract suspend fun query(key:String):CatalogCachedQuery?
    /** 仅选原类型及原ID集合，返回顺序由上层按orderedIds恢复。 */
    @Query("SELECT * FROM catalog_records WHERE kind=:kind AND publicId IN (:ids)") abstract suspend fun records(kind:String,ids:List<String>):List<CatalogCachedRecord>
    /** 记录冲突由后端本次响应覆盖，键包含类型避免不同资源共用同一ID。 */
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putRecords(records:List<CatalogCachedRecord>)
    /** 完整集合与实体一起保存，查询顺序不得被数据库行顺序改写。 */
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun putQuery(query:CatalogCachedQuery)
    /** 同一只读事务拿集合及成员，空完整结果与缺集合不同。 */
    @Transaction open suspend fun snapshot(key:String):CatalogCachedSnapshot? {val q=query(key) ?: return null;return CatalogCachedSnapshot(q,records(q.kind,q.orderedIds))}
    /** 两表同事务保存；成员/类型不完整在写入前拒绝，SQL失败回滚两表，不留下假完整集合。 */
    @Transaction open suspend fun saveSnapshot(query:CatalogCachedQuery,records:List<CatalogCachedRecord>) {
        require(query.complete && records.map{it.publicId}==query.orderedIds && records.all{it.kind==query.kind}){"公开查询成员不完整"}
        require(records.map{it.publicId}.distinct().size==records.size){"公开查询成员重复"}
        putRecords(records);putQuery(query)
    }
}
