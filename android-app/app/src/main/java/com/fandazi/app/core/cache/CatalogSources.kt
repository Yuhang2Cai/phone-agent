// 公开目录来源能力适配：只有显式声明支持来源流的仓库走缓存流，旧接口委托仍调用本对象覆盖的读取。
package com.fandazi.app.core.cache
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.deal.*
import kotlinx.coroutines.flow.*
/** 声明仓库自己的来源流实现，防止Kotlin的by委托将默认方法绑定到另一个对象。 */
interface CatalogSourceRepository
/** 老只读实现直接调用其stores覆盖方法，不让委托对象绕过假依赖或业务行为。 */
fun CatalogRepository.catalogStores(query:CatalogQuery):Flow<CatalogRead<List<CatalogStore>>> = if(this is CatalogSourceRepository)observeStores(query) else flow{emit(CatalogRead.online(stores(query)))}
/** 摘要同样保持原接口动态分派，来源能力必须显式声明。 */
fun CatalogRepository.catalogStore(id:String,query:CatalogQuery):Flow<CatalogRead<CatalogStore>> = if(this is CatalogSourceRepository)observeStore(id,query) else flow{emit(CatalogRead.online(store(id,query)))}
/** 菜品不受默认observe方法的委托接收者影响。 */
fun CatalogRepository.catalogDishes(id:String):Flow<CatalogRead<List<CatalogDish>>> = if(this is CatalogSourceRepository)observeDishes(id) else flow{emit(CatalogRead.online(dishes(id)))}
/** 公开评价保留原覆盖及取消异常，避免误读真实服务。 */
fun CatalogRepository.catalogReviews(id:String):Flow<CatalogRead<List<CatalogReview>>> = if(this is CatalogSourceRepository)observeReviews(id) else flow{emit(CatalogRead.online(reviews(id)))}
/** 套餐详情只有来源仓库才能提供缓存，否则维持原一次读取。 */
fun GroupBuyingRepository.catalogDeal(id:String):Flow<CatalogRead<DealPackage>> = if(this is CatalogSourceRepository)observeDeal(id) else flow{emit(CatalogRead.online(loadDeal(id)))}
/** 同店套餐集合适配与原接口兼容，私人交易不在这些扩展中。 */
fun GroupBuyingRepository.catalogStoreDeals(id:String):Flow<CatalogRead<List<DealPackage>>> = if(this is CatalogSourceRepository)observeStoreDeals(id) else flow{emit(CatalogRead.online(loadStoreDeals(id)))}
