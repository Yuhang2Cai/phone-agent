// 对比页面状态：薄恢复只保存用户有序公开ID；每列独立代次/任务阻挡迟到，依赖只读仓库。
package com.fandazi.app.feature.compare
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
/** 单列事实在刷新中仍可只读辨认，加载/失败/失效时购买入口关闭。 */
data class CompareColumn(val id:String,val facts:CompareDealFacts?=null,val loading:Boolean=true,val error:String?=null) {
    /** 只是导航资格，后续页面必须读取最新事实和报价，不能直接用本快照下单。 */
    val canPurchase:Boolean get()=!loading && error==null && facts?.purchaseAvailable()==true
}
/** 整体选择与列顺序保持用户加入顺序；逐列错误不抹掉其他已成功事实。 */
data class DealCompareState(val ids:List<String> = emptyList(),val columns:List<CompareColumn> = emptyList())
/** 页面 ViewModel 管理独立列读取；持久SavedState不含DTO、金额、凭证或聊天草稿。 */
class DealCompareViewModel(private val repository:DealCompareRepository,private val saved:SavedStateHandle=SavedStateHandle()):ViewModel() {
    private val initial=CompareSelection.restore(saved.get<List<String>>(KEY).orEmpty())
    private val mutable=MutableStateFlow(DealCompareState(initial.ids))
    /** 页面只能收集不可变状态流，所有请求更新须经本类的身份/代次校验。 */
    val state=mutable.asStateFlow()
    private val jobs=mutableMapOf<String,Job>();private val tickets=mutableMapOf<String,Long>();private var generation=0L
    /** 显式进入/返回读取新事实；非法数量不发HTTP，不恢复默认选项。 */
    fun initialize(ids:List<String>) {
        cancelReads();val selected=CompareSelection.restore(ids)
        saved[KEY]=selected.ids
        mutable.value=DealCompareState(selected.ids,if(selected.canCompare)selected.ids.map{CompareColumn(it)}else emptyList())
        if(selected.canCompare)selected.ids.forEach{load(it)}
    }
    /** 局部错误由用户明确重试，只刷新原列，不替换为列表第一项。 */
    fun retry(id:String){if(id in mutable.value.ids)load(id)}
    /** 启动单列任务前先推进票据并关闭购买；不合作旧响应也必须满足本列和整个页面代次。 */
    private fun load(id:String) {
        val pageTicket=generation;val ticket=(tickets[id] ?: 0L)+1;tickets[id]=ticket;jobs.remove(id)?.cancel()
        update(id){it.copy(loading=true,error=null)}
        jobs[id]=viewModelScope.launch {
            try {
                val result=repository.load(id)
                if(pageTicket!=generation || tickets[id]!=ticket || id !in mutable.value.ids)return@launch
                when(result){is ApiResult.Success->{require(result.value.deal.publicId.equals(id,true)){"响应套餐不匹配"};update(id){it.copy(facts=result.value,loading=false,error=null)}}
                    is ApiResult.Failure->update(id){it.copy(loading=false,error="读取失败，点击重试")}}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){if(pageTicket==generation && tickets[id]==ticket)update(id){it.copy(loading=false,error="套餐信息无效，点击重试")}}
        }
    }
    /** 只替换目标列，保持相同资源顺序与其他列事实，不按结果到达顺序重排。 */
    private fun update(id:String,change:(CompareColumn)->CompareColumn){mutable.value=mutable.value.copy(columns=mutable.value.columns.map{if(it.id==id)change(it)else it})}
    /** 页面离开取消所有只读工作并关闭购买；保留公开ID和内存只读事实便于返回识别，之后重新加载。 */
    fun cancelReads(){generation++;jobs.values.forEach{it.cancel()};jobs.clear();tickets.clear();mutable.value=mutable.value.copy(columns=mutable.value.columns.map{it.copy(loading=true)})}
    /** 永久清除模型也取消旧工作，不能由迟到响应复活上一页面。 */
    override fun onCleared(){cancelReads();super.onCleared()}
    companion object {/** 唯一薄恢复键，值只包含公开UUID列表。 */ const val KEY="compareSelectedIds"}
    /** 正式页面通过SavedState注册表装配，不添加生产测试setter。 */
    class Factory(private val repository:DealCompareRepository):ViewModelProvider.Factory {
        /** 创建仅有公开薄引用的模型，持久状态由页面生命周期管理。 */
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>,extras:CreationExtras):T=DealCompareViewModel(repository,extras.createSavedStateHandle()) as T
    }
}
/** Activity范围选择状态负责列表/详情/对比往返，持久化仅含公开ID顺序，无账户资料。 */
class CompareSelectionViewModel(private val saved:SavedStateHandle):ViewModel() {
    private val mutable=MutableStateFlow(CompareSelection.restore(saved.get<List<String>>(DealCompareViewModel.KEY).orEmpty()))
    /** 只读选择供各页面显示计数和已选状态。 */
    val selection=mutable.asStateFlow()
    /** 加入或拒绝第四项；成功后记录公开引用，返回真实接受结果给页面提示。 */
    fun add(id:String):Boolean {val result=mutable.value.add(id);if(result.accepted)publish(result.selection);return result.accepted}
    /** 用户显式移除原引用，不触碰其他页面查询、草稿或滚动。 */
    fun remove(id:String){publish(mutable.value.remove(id))}
    /** 原子发布与薄保存，不将对比资料或资格写入SavedState。 */
    private fun publish(value:CompareSelection){mutable.value=value;saved[DealCompareViewModel.KEY]=value.ids}
}
