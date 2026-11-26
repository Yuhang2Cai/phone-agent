// 门店浏览状态：同一公开门店内摘要与页签并行读取，回调代次隔离，薄保存页签和公开锚点。
package com.fandazi.app.feature.store

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.cache.*
import kotlinx.coroutines.flow.map
import com.fandazi.app.feature.deal.GroupBuyingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 三个固定浏览页签只是界面结构，不持有写库存、加购或交易资格。 */
enum class StoreTab(val label:String) {DEALS("优惠"),DISHES("菜品"),REVIEWS("评价")}

/** 摘要及列表各自加载/失败，不因局部失败把其他可信区改成假成功。 */
data class StoreBrowseState(val storeId:String="",val query:CatalogQuery=CatalogQuery(),val tab:StoreTab=StoreTab.DEALS,
    val summary:CatalogStore?=null,val summaryError:Boolean=false,val summaryLoading:Boolean=true,
    val loading:Boolean=true,val error:Boolean=false,val rows:List<StoreDetailRow> = emptyList(),val metadata:CatalogReadMetadata=CatalogReadMetadata(),val summaryMetadata:CatalogReadMetadata=CatalogReadMetadata())

/** 页面生命周期保留状态；公开数据只在内存，进程重建仅重读导航门店及薄页签。 */
class StoreBrowseViewModel(private val catalog:CatalogRepository,private val buying:GroupBuyingRepository,private val saved:SavedStateHandle=SavedStateHandle()):ViewModel() {
    private val mutable=MutableStateFlow(StoreBrowseState(tab=StoreTab.entries.getOrElse(saved["tab"] ?: 0){StoreTab.DEALS}))
    /** 页面订阅只读快照，不允许视图或后台Diff直接改写业务事实。 */
    val state=mutable.asStateFlow()
    private var summaryJob:Job?=null
    private var tabJob:Job?=null
    private var generation=0L
    private var summaryGeneration=0L
    /** 锚点按页签分别保存完整公开行键，切换不把菜单位置套在评价行上。 */
    val anchor:ListAnchor get()=ListAnchor(saved["anchor.${mutable.value.tab.name}.key"],saved["anchor.${mutable.value.tab.name}.offset"] ?: 0)
    /** 滚动或切换前保存当前页签引用，不序列化菜品、评价内容或真实金额。 */
    fun rememberAnchor(value:ListAnchor){val prefix="anchor.${mutable.value.tab.name}";saved["$prefix.key"]=value.key;saved["$prefix.offset"]=value.offset}
    /** 相同门店的视图返回沿用内存事实；新门店清旧集合并启动两个独立只读查询。 */
    fun initialize(id:String,query:CatalogQuery) {
        if(mutable.value.storeId==id && mutable.value.query==query && id.isNotBlank())return
        generation++;summaryGeneration++;summaryJob?.cancel();tabJob?.cancel()
        mutable.value=StoreBrowseState(storeId=id,query=query,tab=mutable.value.tab)
        loadSummary();select(mutable.value.tab,true)
    }
    /** 业务页面返回必须读取最新摘要及当前页签；明确刷新不受同条件在途/成功去重影响。 */
    fun refreshAfterBusinessReturn() {
        if(mutable.value.storeId.isBlank())return
        // 两个只读请求独立启动，不等待摘要后再读评价；原页签和薄滚动引用保持。
        loadSummary();select(mutable.value.tab,true)
    }
    /** 只更新门店摘要；评价在途/成功不因摘要晚到再次读取，评分摘要由独立行差量显示。 */
    fun loadSummary() {
        val before=mutable.value;val ticket=++summaryGeneration;summaryJob?.cancel()
        mutable.value=before.copy(summaryLoading=true,summaryError=false,summaryMetadata=before.summaryMetadata.copy(status=CatalogReadStatus.REFRESHING))
        summaryJob=viewModelScope.launch {
            try {
                catalog.catalogStore(before.storeId,before.query).collect { read->
                    if(ticket!=summaryGeneration || mutable.value.storeId!=before.storeId)return@collect
                    mutable.value=when(val result=read.result) {
                        is ApiResult.Success->{require(result.value.publicId==before.storeId);mutable.value.copy(summary=result.value.copy(reviewDistribution=result.value.reviewDistribution.toList()),summaryLoading=false,summaryError=false,summaryMetadata=read.metadata)}
                        is ApiResult.Failure->mutable.value.copy(summary=null,summaryLoading=false,summaryError=true,summaryMetadata=read.metadata)
                    }
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){if(ticket==summaryGeneration)mutable.value=mutable.value.copy(summary=null,summaryLoading=false,summaryError=true)}
        }
    }
    /** 换页签清旧行，同页签刷新保留完整公开快照及锚点；取消读取并核对门店/页签/代次。 */
    fun select(tab:StoreTab,force:Boolean=false) {
        if(!force && tab==mutable.value.tab && tabJob?.isActive==true)return
        if(!force && tab==mutable.value.tab && !mutable.value.loading && !mutable.value.error)return
        val ticket=++generation;tabJob?.cancel();val id=mutable.value.storeId
        val before=mutable.value
        // 恢复或明确重试相同页签时，已有完整集合保持只读；换页签绝不能借用上一页签成员。
        saved["tab"]=tab.ordinal;mutable.value=before.copy(tab=tab,loading=true,error=false,
            rows=if(tab==before.tab)before.rows else emptyList(),
            metadata=before.metadata.copy(status=CatalogReadStatus.REFRESHING))
        tabJob=viewModelScope.launch {
            try {
                val reads=when(tab) {
                    StoreTab.DEALS->buying.catalogStoreDeals(id).map{r->r.map{CatalogRowIdentity.deals(it,id).map{deal->StoreDetailRow.Deal(deal) as StoreDetailRow}}}
                    StoreTab.DISHES->catalog.catalogDishes(id).map{r->r.map{CatalogRowIdentity.dishes(it).map{dish->StoreDetailRow.Dish(dish,id) as StoreDetailRow}}}
                    StoreTab.REVIEWS->catalog.catalogReviews(id).map{r->r.map{CatalogRowIdentity.reviews(it,id).map{review->StoreDetailRow.Review(review) as StoreDetailRow}}}
                }
                reads.collect { read->
                    // 同一页签的每个来源阶段均核对门店/代次，不按到达顺序拼接数据。
                    if(ticket!=generation || mutable.value.storeId!=id || mutable.value.tab!=tab)return@collect
                    mutable.value=when(val result=read.result){is ApiResult.Success->mutable.value.copy(loading=false,error=false,rows=result.value.toList(),metadata=read.metadata);is ApiResult.Failure->mutable.value.copy(loading=false,error=true,rows=emptyList(),metadata=read.metadata)}
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){if(ticket==generation)mutable.value=mutable.value.copy(loading=false,error=true,rows=emptyList())}
        }
    }
    /** 使用正式应用仓库与当前Fragment薄保存注册表，页面无第二套数据服务。 */
    class Factory(private val catalog:CatalogRepository,private val buying:GroupBuyingRepository):ViewModelProvider.Factory {
        /** 由当前生命周期持有唯一模型；返回对应类型，不执行网络或额外身份装配。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>,extras:CreationExtras):T=StoreBrowseViewModel(catalog,buying,extras.createSavedStateHandle()) as T
    }
}
