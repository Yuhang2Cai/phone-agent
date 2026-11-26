// 浏览状态模块：保留条件及薄锚点，公开业务事实重新读取；依赖CatalogRepository与SavedStateHandle。
package com.fandazi.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.cache.*
import com.fandazi.app.feature.store.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 查询状态不把加载失败当空结果；整个列表的后端顺序和内容是一次不可变快照。 */
data class CatalogState(val query:CatalogQuery=CatalogQuery(),val stores:List<CatalogStore> = emptyList(),val loading:Boolean=false,val error:String?=null,val metadata:CatalogReadMetadata=CatalogReadMetadata())

/** 每个页面独立持有查询代次，外部读取不合作取消时也不能交付过期成功或失败。 */
class CatalogViewModel(private val repository:CatalogRepository,private val saved:SavedStateHandle=SavedStateHandle()):ViewModel() {
    private val mutable=MutableStateFlow(CatalogState(restoredQuery() ?: CatalogQuery()))
    /** 只读状态供当前视图观察，后台回调不持有Fragment或XML控件。 */
    val state=mutable.asStateFlow()
    private var request:Job?=null
    private var generation=0L
    private var initialized=false
    /** 保存首个可见公开资源和像素偏移，无列表、价格或用户资格进入Bundle。 */
    val anchor:ListAnchor get()=ListAnchor(saved["anchor.key"],saved["anchor.offset"] ?: 0)
    /** 列表滑动及离开时调用，重建只恢复位置引用，不恢复旧业务事实。 */
    fun rememberAnchor(value:ListAnchor) {saved["anchor.key"]=value.key;saved["anchor.offset"]=value.offset}
    /** 首次优先使用已经保存的查询；导航参数只用于无历史状态的新页面。 */
    fun initialize(query:CatalogQuery) {if(!initialized){initialized=true;load(restoredQuery() ?: query)}}
    /** 查询切换清空旧行及旧锚点；相同条件重试保留锚点，成功才有可点击新结果。 */
    fun load(query:CatalogQuery=mutable.value.query) {
        val ticket=++generation;request?.cancel()
        if(query!=mutable.value.query) rememberAnchor(ListAnchor())
        // 相同条件恢复/重试保留当前事实，条件切换仍清旧行；缓存先显后由来源流更新。
        val previous=mutable.value
        persistQuery(query);mutable.value=CatalogState(query,stores=if(query==previous.query)previous.stores else emptyList(),loading=true,metadata=previous.metadata.copy(status=CatalogReadStatus.REFRESHING))
        request=viewModelScope.launch {
            try {
                repository.catalogStores(query).collect { read->
                    // 每次缓存/在线交付都核对代次和完整查询身份，迟到旧响应不能覆盖新条件。
                    if(ticket!=generation || mutable.value.query!=query)return@collect
                    mutable.value=when(val result=read.result) {
                        is ApiResult.Success->CatalogState(query,CatalogRowIdentity.stores(result.value),metadata=read.metadata)
                        is ApiResult.Failure->CatalogState(query,error="门店加载失败，点击重试",metadata=read.metadata)
                    }
                }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){if(ticket==generation)mutable.value=CatalogState(query,error="门店加载失败，点击重试")}
        }
    }
    /** 保存独立条件的原始字段，位置未知保持null，不序列化业务集合或默认坐标。 */
    private fun persistQuery(query:CatalogQuery) {
        saved["query.ready"]=true;saved["query.keyword"]=query.keyword;saved["query.category"]=query.category
        saved["query.sort"]=query.sort;saved["query.city"]=query.cityCode;saved["query.lat"]=query.latitude;saved["query.lon"]=query.longitude
    }
    /** 恢复薄条件后按原接口重新校验事实，不将缓存身份误认成当前业务成功。 */
    private fun restoredQuery():CatalogQuery?=if(saved.get<Boolean>("query.ready")!=true)null else CatalogQuery(
        saved["query.keyword"] ?: "",saved["query.category"] ?: "推荐",saved["query.sort"] ?: "RECOMMENDED",saved["query.city"],saved["query.lat"],saved["query.lon"])
    /** 原生产仓库和Fragment保存注册表统一装配，不建立第二个服务地址。 */
    class Factory(private val repository:CatalogRepository):ViewModelProvider.Factory {
        /** 生命周期提供的CreationExtras绑定当前页面的薄状态保存。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>,extras:CreationExtras):T=CatalogViewModel(repository,extras.createSavedStateHandle()) as T
    }
}
