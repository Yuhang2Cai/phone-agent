// 公开目录数据库：应用内独立public-catalog.db，懒打开且IO访问；迁移只清本库，不触碰私人业务。
package com.fandazi.app.core.cache
import android.content.Context
import androidx.room.*
import com.google.gson.JsonParser
/** 有序ID转换只读写JSON字符串数组，不接受对象、账户字段或任意原HTTP正文。 */
class CatalogCacheConverters {
    /** 保留ID原顺序，转义由Gson完成，重复是否合法由读取引擎判断。 */
    @TypeConverter fun encode(ids:List<String>):String=com.google.gson.Gson().toJson(ids)
    /** 非数组/非字符串明确失败，不能静默补空列表掩盖损坏缓存。 */
    @TypeConverter fun decode(value:String):List<String> {val array=JsonParser.parseString(value).asJsonArray;return array.map{require(it.isJsonPrimitive && it.asJsonPrimitive.isString){"公开ID集合格式错误"};it.asString}}
}
/** Room版本仅覆盖新增公开库；实体载荷还有独立schemaVersion防止旧结构反序列化。 */
@Database(entities=[CatalogCachedRecord::class,CatalogCachedQuery::class],version=1,exportSchema=true)
@TypeConverters(CatalogCacheConverters::class)
abstract class CatalogDatabase:RoomDatabase() {
    /** 唯一DAO没有任何私人业务或凭证写入方法。 */
    abstract fun catalog():CatalogCacheDao
    companion object {
        /** 正式库固定名称，只允许对本纯公开库执行版本不兼容清除。 */
        const val NAME="public-catalog.db"
        /** 创建配置时不读磁盘；Room首次后台调用才打开，不改原加密会话启动流程。 */
        fun create(context:Context,name:String=NAME):CatalogDatabase=Room.databaseBuilder(context.applicationContext,CatalogDatabase::class.java,name)
            .fallbackToDestructiveMigration(true).fallbackToDestructiveMigrationOnDowngrade(true).build()
    }
}
/** 延迟装配数据库且在IO调度器操作，磁盘异常由只读引擎隔离。 */
class RoomCatalogCacheStore(private val database:()->CatalogDatabase):CatalogCacheStore {
    /** 事务读返回公开集合和成员，不包含其它库的表。 */
    override suspend fun read(key:String)=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){database().catalog().snapshot(key)}
    /** 两表原子写在后台完成，页面取消会向Room传播。 */
    override suspend fun save(query:CatalogCachedQuery,records:List<CatalogCachedRecord>)=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){database().catalog().saveSnapshot(query,records)}
}
