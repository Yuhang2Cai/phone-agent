// 私有订单列表状态：按完整认证版本隔离内存事实，返回必须重新读取资格，不新增Room或订单持久缓存。
package com.fandazi.app.feature.order

import androidx.lifecycle.*
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.voucher.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 错误与无订单不同，认证拒绝保持登录语义；事实只来自当前账号最近的读取。 */
data class OrdersState(val orders:List<GroupOrderSummary> = emptyList(),val segment:OrderSegment=OrderSegment.ALL,
    val loading:Boolean=false,val error:ApiError?=null,val authRequired:Boolean=false)

/** 保留同一Fragment返回的分类与锚点，但始终执行onResume规定刷新及迟到身份校验。 */
class OrdersViewModel(private val repository:VoucherRepository,private val sessions:StateFlow<SessionState>):ViewModel() {
    private var owner=sessions.value as? SessionState.Authenticated
    private val mutable=MutableStateFlow(OrdersState(authRequired=owner==null))
    /** 页面订阅只读快照，不允许视图或后台Diff直接改写业务事实。 */
    val state=mutable.asStateFlow()
    private var request:Job?=null
    private var generation=0L
    /** 仅内存UI引用；账号改变或退出清除，不持久化任何订单快照。 */
    var anchor=ListAnchor()
        private set
    init {viewModelScope.launch{sessions.collect{current->changeOwner(current as? SessionState.Authenticated)}}}
    /** 任意可执行导航都核对实际身份，防止Diff完成前旧卡片仍可点击。 */
    fun currentOwnerMatches():Boolean=owner!=null && owner==sessions.value
    /** 薄锚点只属于当前内存账号，认证已改变时不接受旧视图的离开回调。 */
    fun rememberAnchor(value:ListAnchor){if(currentOwnerMatches())anchor=value}
    /** 分类只过滤权威订单枚举，不更改业务事实或后端资格。 */
    fun select(segment:OrderSegment){if(segment!=mutable.value.segment){anchor=ListAnchor();mutable.value=mutable.value.copy(segment=segment)}}
    /** 身份变化先使代次失效并清集合，再发起后续读取；同用户名新token也视为新认证版本。 */
    private fun changeOwner(current:SessionState.Authenticated?) {
        if(current==owner)return
        generation++;request?.cancel();owner=current;anchor=ListAnchor()
        mutable.value=OrdersState(authRequired=current==null)
    }
    /** 每次返回读取真实付款、券及售后状态；只合并同一认证版本的在途请求，不吞掉交易后的新读取。 */
    fun refresh(force:Boolean=true) {
        changeOwner(sessions.value as? SessionState.Authenticated)
        val expected=owner
        if(expected==null){mutable.value=OrdersState(authRequired=true);return}
        if(request?.isActive==true && !force)return
        // 交易/售后返回强制开启新读取；进入详情前的在途结果不能当作写业务后的新资格。
        if(force)request?.cancel()
        val ticket=++generation
        mutable.value=mutable.value.copy(orders=emptyList(),loading=true,error=null,authRequired=false)
        request=viewModelScope.launch {
            try {
                val result=repository.listOrders()
                if(ticket!=generation || owner!=expected || sessions.value!=expected)return@launch
                mutable.value=when(result){
                    is ApiResult.Success->mutable.value.copy(orders=CatalogRowIdentity.orders(result.value),loading=false)
                    is ApiResult.Failure->mutable.value.copy(orders=emptyList(),loading=false,error=result.error,authRequired=(result.error as? ApiError.Http)?.code==401)
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){if(ticket==generation && currentOwnerMatches())mutable.value=mutable.value.copy(orders=emptyList(),loading=false,error=ApiError.Network("订单数据格式无效"))}
        }
    }
    /** 应用唯一订单仓库/身份流注入，不创建另一套私人状态源或令牌供应器。 */
    class Factory(private val repository:VoucherRepository,private val sessions:StateFlow<SessionState>):ViewModelProvider.Factory {
        /** 由当前生命周期持有唯一模型；返回对应类型，不执行网络或额外身份装配。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>):T=OrdersViewModel(repository,sessions) as T
    }
}
