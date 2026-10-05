// 对比只读仓库：逐个读取用户选择的原套餐，再读取其权威门店名称，依赖既有公开目录接口。
package com.fandazi.app.feature.compare
import com.fandazi.app.core.network.*
import com.fandazi.app.core.cache.*
import kotlinx.coroutines.flow.*
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.feature.deal.DealDetailValidation
import com.fandazi.app.feature.store.CatalogRepository
import com.fandazi.app.feature.store.CatalogQuery
/** 此接口没有报价、订单或写入方法，客户端缓存价格不能进入交易流程。 */
fun interface DealCompareRepository {
    /** 返回指定ID的最新事实或明确读取错误，取消原样传播。 */
    suspend fun load(id:String):ApiResult<CompareDealFacts>
    /** 默认保持原单次读取兼容，正式公开仓库覆盖以携带缓存来源。 */
    fun observe(id:String):Flow<CatalogRead<CompareDealFacts>> = flow{emit(CatalogRead.online(load(id)))}
}
/** 原目录同一认证/取消网络客户端，旧服务器未提供名称时只读真实门店，不用默认名。 */
class HttpDealCompareRepository(private val buying:GroupBuyingRepository,private val catalog:CatalogRepository):DealCompareRepository {
    /** 字段和身份验证后才构造事实；错误资源或门店不匹配转为明确格式失败。 */
    override suspend fun load(id:String):ApiResult<CompareDealFacts> = observe(id).last().result
    /** 每个来源阶段均验证原ID；名称旧服务回退也保留其来源，不能把离线名称混成新资格。 */
    override fun observe(id:String):Flow<CatalogRead<CompareDealFacts>> = buying.catalogDeal(id).map { read->
        when(val result=read.result) {
            is ApiResult.Failure->CatalogRead(result,read.metadata)
            is ApiResult.Success->{
                val deal=result.value
                try {
                    DealDetailValidation.fields(deal);require(deal.publicId.equals(id,true)){"对比资源身份错误"}
                    val name=deal.storeName?.takeIf{it.isNotBlank()}
                    if(name!=null)CatalogRead(ApiResult.Success(CompareDealFacts(deal,name,metadata=read.metadata)),read.metadata)
                    else {
                        val store=catalog.catalogStore(deal.storePublicId,CatalogQuery()).last()
                        val metadata=if(read.metadata.canTransact)store.metadata else read.metadata
                        when(val value=store.result) {
                            is ApiResult.Failure->CatalogRead(value,metadata)
                            is ApiResult.Success->{require(value.value.publicId==deal.storePublicId){"对比门店身份无效"};CatalogRead(ApiResult.Success(CompareDealFacts(deal,value.value.name,metadata=metadata)),metadata)}
                        }
                    }
                }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                catch(invalid:IllegalArgumentException){CatalogRead(ApiResult.Failure(ApiError.Network("对比套餐信息无效")),read.metadata)}
            }
        }
    }
}
