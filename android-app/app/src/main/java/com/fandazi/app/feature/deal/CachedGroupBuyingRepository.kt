// 套餐公开缓存装饰器：只读详情/门店套餐集合；报价、确认及订单仓库维持在线独立边界。
package com.fandazi.app.feature.deal
import com.fandazi.app.core.cache.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
/** 统一公开缓存引擎确保从首页、详情、对比读到相同来源语义。 */
class CachedGroupBuyingRepository(private val online:GroupBuyingRepository,private val engine:CatalogCacheEngine):GroupBuyingRepository,CatalogSourceRepository {
    /** 详情键包含原套餐ID，不能拿当前门店首项替换。 */
    override fun observeDeal(id:String)=engine.read(CatalogCachePolicy.key("deal",id),"DEAL",PublicCatalogCacheCodecs.single(PublicCatalogCacheCodecs.deals)){online.loadDeal(id)}.flowOn(Dispatchers.IO)
    /** 同店套餐缓存保留原ID顺序，成功空列表也是完整结果。 */
    override fun observeStoreDeals(id:String)=engine.read(CatalogCachePolicy.key("store-deals",id),"DEAL",PublicCatalogCacheCodecs.deals){online.loadStoreDeals(id)}.flowOn(Dispatchers.IO)
    /** 兼容旧只读调用，页面要通过observe携带来源后才可判断资格。 */
    override suspend fun loadDeal(dealPublicId:String)=observeDeal(dealPublicId).last().result
    override suspend fun loadStoreDeals(storePublicId:String)=observeStoreDeals(storePublicId).last().result
}
