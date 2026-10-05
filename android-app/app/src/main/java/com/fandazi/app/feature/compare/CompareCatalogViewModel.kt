// 对比选择目录状态：只读加载后端完整门店/套餐列表，保留服务端顺序；依赖原目录仓库，不持久化DTO。
package com.fandazi.app.feature.compare
import androidx.lifecycle.*
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.feature.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 局部读取失败显式标注不完整，不能将失败当成真实空目录。 */
data class CompareCatalogState(val rows:List<CompareDealFacts> = emptyList(),val loading:Boolean=false,val error:Boolean=false)
/** 有界并发读取门店套餐，选择状态由Activity薄模型单独管理。 */
class CompareCatalogViewModel(private val catalog:CatalogRepository,private val buying:GroupBuyingRepository):ViewModel() {
    private val mutable=MutableStateFlow(CompareCatalogState());val state=mutable.asStateFlow()
    private var job:Job?=null;private var generation=0L
    /** 用户进入/重试时刷新；同条件在途工作不重复发起。 */
    fun load(){if(job?.isActive==true)return;val ticket=++generation;mutable.value=mutable.value.copy(loading=true,error=false)
        job=viewModelScope.launch {
            try {
                when(val result=catalog.stores(CatalogQuery())) {
                    is ApiResult.Failure->if(ticket==generation)mutable.value=mutable.value.copy(loading=false,error=true)
                    is ApiResult.Success->{
                        // 多门店允许跨店比较；最多四个只读请求并发，awaitAll保持目录与套餐原返回顺序。
                        val limit=Semaphore(4)
                        val responses=coroutineScope{result.value.map{store->async{limit.withPermit{store to buying.loadStoreDeals(store.publicId)}}}.awaitAll()}
                        val failed=responses.any{it.second is ApiResult.Failure}
                        val rows=responses.flatMap{(store,deals)->(deals as? ApiResult.Success)?.value.orEmpty().map{deal->
                            require(deal.storePublicId==store.publicId){"套餐归属无效"};CompareDealFacts(deal,store.name)
                        }}
                        require(rows.map{it.deal.publicId}.distinct().size==rows.size){"套餐身份重复"}
                        if(ticket==generation)mutable.value=CompareCatalogState(rows,false,failed)
                    }
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){if(ticket==generation)mutable.value=mutable.value.copy(loading=false,error=true)}
        }
    }
    /** 离开取消只读任务，旧响应不能覆盖重建视图，已有事实仅留内存。 */
    fun cancel(){generation++;job?.cancel();job=null;mutable.value=mutable.value.copy(loading=false)}
    override fun onCleared(){cancel();super.onCleared()}
    /** 工厂仅装配真实仓库，无运行时演示数据。 */
    class Factory(private val catalog:CatalogRepository,private val buying:GroupBuyingRepository):ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(type:Class<T>):T=CompareCatalogViewModel(catalog,buying) as T
    }
}
