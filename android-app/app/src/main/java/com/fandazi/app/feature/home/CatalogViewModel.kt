// 浏览状态模块：保存查询与真实结果，依赖 CatalogRepository；视图重建不会遗失分类或城市选择。
package com.fandazi.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.store.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 浏览加载状态，错误与空目录是两个不同状态。 */
data class CatalogState(val query: CatalogQuery = CatalogQuery(), val stores: List<CatalogStore> = emptyList(),
    val loading: Boolean = false, val error: String? = null)

/** 一页一个 ViewModel；取消旧查询并核对条件，防止慢响应覆盖用户刚选的新分类。 */
class CatalogViewModel(private val repository: CatalogRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CatalogState())
    /** 页面仅观察状态，不能自行修改服务器结果或排序。 */
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var initialized = false
    /** 当前页面滚动偏移，仅保存浏览状态而非业务事实；详情返回时恢复局部浏览位置。 */
    var scrollY: Int = 0
        private set

    /** 视图销毁前保存实际偏移，不触发结果重绘；负值规范为页面顶部。 */
    fun rememberScroll(offset: Int) { scrollY = offset.coerceAtLeast(0) }

    /** 首次按导航条件加载；返回已有页面时继续保留其查询状态。 */
    fun initialize(query: CatalogQuery) { if (!initialized) { initialized = true; load(query) } }

    /** 查询前清掉旧业务结果；失败显示重试，不把旧集合当本次成功结果。 */
    fun load(query: CatalogQuery = mutable.value.query) {
        request?.cancel()
        // 条件变化从新结果顶部开始；原查询重试或页面恢复不丢掉用户已浏览的位置。
        if (query != mutable.value.query) scrollY = 0
        mutable.value = CatalogState(query, loading = true)
        request = viewModelScope.launch {
            when (val result = repository.stores(query)) {
                is ApiResult.Success -> mutable.value = CatalogState(query, result.value)
                is ApiResult.Failure -> mutable.value = CatalogState(query, error = "门店加载失败，点击重试")
            }
        }
    }

    /** 向 Android 生命周期提供现有仓库实例，不初始化另一服务地址。 */
    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        /** 只创建本模块 ViewModel，生命周期由 Fragment 管理。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CatalogViewModel(repository) as T
    }
}
