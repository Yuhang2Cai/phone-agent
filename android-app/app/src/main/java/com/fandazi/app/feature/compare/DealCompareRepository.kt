// 对比只读仓库：逐个读取用户选择的原套餐，再读取其权威门店名称，依赖既有公开目录接口。
package com.fandazi.app.feature.compare
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.feature.deal.DealDetailValidation
import com.fandazi.app.feature.store.CatalogRepository
import com.fandazi.app.feature.store.CatalogQuery
/** 此接口没有报价、订单或写入方法，客户端缓存价格不能进入交易流程。 */
fun interface DealCompareRepository {
    /** 返回指定ID的最新事实或明确读取错误，取消原样传播。 */
    suspend fun load(id:String):ApiResult<CompareDealFacts>
}
/** 原目录同一认证/取消网络客户端，旧服务器未提供名称时只读真实门店，不用默认名。 */
class HttpDealCompareRepository(private val buying:GroupBuyingRepository,private val catalog:CatalogRepository):DealCompareRepository {
    /** 字段和身份验证后才构造事实；错误资源或门店不匹配转为明确格式失败。 */
    override suspend fun load(id:String):ApiResult<CompareDealFacts> {
        val result=buying.loadDeal(id)
        if(result is ApiResult.Failure)return result
        val deal=(result as ApiResult.Success).value
        try {DealDetailValidation.fields(deal);require(deal.publicId.equals(id,true)){"对比资源身份错误"}}
        catch(invalid:IllegalArgumentException){return ApiResult.Failure(ApiError.Network("对比套餐信息无效"))}
        val name=deal.storeName?.takeIf{it.isNotBlank()}
        if(name!=null)return ApiResult.Success(CompareDealFacts(deal,name))
        // 缺少新名称字段的旧服务按原接口查询门店，不推断标题、照片或其他套餐资料。
        return when(val store=catalog.store(deal.storePublicId,CatalogQuery())) {
            is ApiResult.Failure->store
            is ApiResult.Success->if(store.value.publicId==deal.storePublicId)ApiResult.Success(CompareDealFacts(deal,store.value.name))else ApiResult.Failure(ApiError.Network("对比门店信息无效"))
        }
    }
}
