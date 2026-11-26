// 公开目录缓存装饰器：只装配门店/城市/菜品/公开评价读取，私人API和写请求完全不经过此模块。
package com.fandazi.app.feature.store
import com.fandazi.app.core.cache.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
/** 运行期始终从原HTTP仓库读取事实，Room只保存成功公开投影，没有演示或受保护数据兜底。 */
class CachedCatalogRepository(private val online:CatalogRepository,private val engine:CatalogCacheEngine):CatalogRepository,CatalogSourceRepository {
    /** 同条件缓存先显；带坐标或距离排序时键为null，不保存定位信息。 */
    override fun observeStores(query:CatalogQuery)=engine.read(CatalogCachePolicy.queryKey("stores",query),"STORE",PublicCatalogCacheCodecs.stores){online.stores(query)}.flowOn(Dispatchers.IO)
    /** 原门店ID也进入完整条件键，距离派生字段编码时裁剪。 */
    override fun observeStore(id:String,query:CatalogQuery)=engine.read(CatalogCachePolicy.queryKey("store:$id",query),"STORE",PublicCatalogCacheCodecs.single(PublicCatalogCacheCodecs.stores)){online.store(id,query)}.flowOn(Dispatchers.IO)
    /** 菜品归属由端点键固定，顺序沿原HTTP返回。 */
    override fun observeDishes(id:String)=engine.read(CatalogCachePolicy.key("dishes",id),"DISH",PublicCatalogCacheCodecs.dishes){online.dishes(id)}.flowOn(Dispatchers.IO)
    /** 公开评价只有公开摘要编码器，无用户账户对象。 */
    override fun observeReviews(id:String)=engine.read(CatalogCachePolicy.key("reviews",id),"REVIEW",PublicCatalogCacheCodecs.reviews){online.reviews(id)}.flowOn(Dispatchers.IO)
    /** 兼容原只读调用：等待当前网络尝试完成再返回，来源敏感页面使用observe接口。 */
    override suspend fun stores(query:CatalogQuery)=observeStores(query).last().result
    override suspend fun store(id:String,query:CatalogQuery)=observeStore(id,query).last().result
    override suspend fun dishes(id:String)=observeDishes(id).last().result
    override suspend fun reviews(id:String)=observeReviews(id).last().result
    /** 城市公共筛选选项也按实际响应缓存，不加入固定城市名单。 */
    override suspend fun cities()=engine.read(CatalogCachePolicy.key("cities"),"CITY",PublicCatalogCacheCodecs.cities){online.cities()}.flowOn(Dispatchers.IO).last().result
}
