// 套餐详情状态模块：实际仓库只读查询分两阶段提交，协程取消和请求代次共同隔离迟到响应。
package com.fandazi.app.feature.deal

import androidx.lifecycle.*
import com.fandazi.app.core.cache.*
import androidx.lifecycle.viewmodel.CreationExtras
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.store.CatalogRowIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 生命周期内保留只读快照；薄恢复仅含公开套餐/门店ID，私有资格和金额不能随进程持久化。 */
class DealDetailViewModel(private val repository:GroupBuyingRepository,private val saved:SavedStateHandle=SavedStateHandle()):ViewModel() {
    private val mutable=MutableStateFlow(DealDetailLoadState())
    /** 视图只订阅不可变快照，不直接把列表选项当作已经校验的详情或交易事实。 */
    val state=mutable.asStateFlow()
    private var generation=0L
    private var optionsGeneration=0L
    private var detailJob:Job?=null
    private var optionsJob:Job?=null

    /** 接收完整导航ID；同一次在途/成功视图绑定合并，切换套餐/门店立即清旧购买资格并取消读取。 */
    fun initialize(id:String,storeId:String="",force:Boolean=false) {
        val before=mutable.value
        if(!force && before.requestedId==id && before.requestedStoreId==storeId && (before.detailLoading || before.detail!=null))return
        invalidateReads()
        val ticket=generation
        mutable.value=DealDetailLoadState(requestedId=id,requestedStoreId=storeId,detailLoading=true)
        // 输入校验在发HTTP前执行，错误路径不能借默认门店/首个套餐产生业务事实。
        if(runCatching{CatalogRowIdentity.publicId(id);if(storeId.isNotBlank())CatalogRowIdentity.publicId(storeId)}.isFailure) {
            mutable.value=mutable.value.copy(detailLoading=false,detailError="套餐信息无效");return
        }
        saved["selectedDealId"]=id;saved["storeId"]=storeId
        detailJob=viewModelScope.launch {
            try {
                repository.catalogDeal(id).collect { read->
                    if(ticket!=generation)return@collect
                    mutable.value=mutable.value.copy(metadata=read.metadata)
                    val result=read.result
                    if(result is ApiResult.Failure){detailFailure(ticket,"套餐详情加载失败，点击重试");return@collect}
                    val actual=(result as ApiResult.Success).value
                    DealDetailValidation.fields(actual);require(actual.publicId==id){"套餐详情身份不匹配"}
                    if(storeId.isNotBlank() && storeId!=actual.storePublicId){detailFailure(ticket,"套餐与门店不匹配");return@collect}
                    // 来源与事实一起先显，缓存不解锁购买；在线/缓存后续交付各重新读取同店选项。
                    mutable.value=mutable.value.copy(detail=actual,detailLoading=false,detailError=null,optionsLoading=true,metadata=read.metadata)
                    loadOptions(actual,ticket)
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){detailFailure(ticket,"套餐详情信息无效，点击重试")}
        }
    }

    /** 详情失败显式重试当前引用，不用错误响应或列表首项作为导航资源。 */
    fun retryDetail(){val current=mutable.value;initialize(current.requestedId,current.requestedStoreId,true)}

    /** 选项失败局部重试只读真实详情的门店；可信内容保留，旧列表响应被独立代次挡住。 */
    fun retryOptions(){val current=mutable.value;current.detail?.let{loadOptions(it,generation)}}

    /** 发起同店选项查询并验证每个身份/归属/字段；缺当前成员保留只读详情且明确禁购。 */
    private fun loadOptions(actual:DealPackage,detailTicket:Long) {
        optionsGeneration++;optionsJob?.cancel();val ticket=optionsGeneration
        mutable.value=mutable.value.copy(options=emptyList(),optionsLoading=true,optionsError=null)
        optionsJob=viewModelScope.launch {
            try {
                repository.catalogStoreDeals(actual.storePublicId).collect { read->
                    if(detailTicket!=generation || ticket!=optionsGeneration || mutable.value.detail?.publicId!=actual.publicId)return@collect
                    mutable.value=mutable.value.copy(optionsMetadata=read.metadata)
                    val result=read.result
                    if(result is ApiResult.Failure){optionsFailure(detailTicket,ticket,"套餐选项加载失败，点击重试");return@collect}
                    val rows=CatalogRowIdentity.deals((result as ApiResult.Success).value,actual.storePublicId);rows.forEach{DealDetailValidation.fields(it)}
                    // 原成员/归属成立也只允许在线事实解锁，恢复购买仍是后续新报价与确认。
                    mutable.value=mutable.value.copy(options=rows,optionsLoading=false,optionsError=if(rows.none{it.publicId==actual.publicId})"当前套餐已不可购买，点击重试"else null,optionsMetadata=read.metadata)
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){optionsFailure(detailTicket,ticket,"套餐选项信息无效，点击重试")}
        }
    }

    /** 详情失败只在本代次构造空事实；不可继续查询同店列表或保留旧金额。 */
    private fun detailFailure(ticket:Long,message:String){if(ticket==generation)mutable.value=mutable.value.copy(detail=null,detailLoading=false,detailError=message,options=emptyList(),optionsLoading=false,optionsError=null)}
    /** 局部失败保留可信详情；明确重试与禁购，不把错误结果写成空成功。 */
    private fun optionsFailure(detailTicket:Long,ticket:Long,message:String){if(detailTicket==generation && ticket==optionsGeneration)mutable.value=mutable.value.copy(options=emptyList(),optionsLoading=false,optionsError=message)}
    /** 先推进代次再取消，外部读取即使返回时取消检查不足仍不能重新发布旧实体。 */
    private fun invalidateReads(){generation++;optionsGeneration++;detailJob?.cancel();optionsJob?.cancel();detailJob=null;optionsJob=null}
    /** 视图退出清只读事实与购买资格并取消请求，保留薄公开引用以便重建后重新读取。 */
    fun cancelReads(){invalidateReads();mutable.value=DealDetailLoadState(requestedId=mutable.value.requestedId,requestedStoreId=mutable.value.requestedStoreId)}
    /** 页面永久销毁同样取消读取，禁止旧ViewModel的非合作回调继续发布状态。 */
    override fun onCleared(){invalidateReads();super.onCleared()}
    /** 使用正式应用唯一只读仓库和Fragment保存注册表，不增加测试装配入口。 */
    class Factory(private val repository:GroupBuyingRepository):ViewModelProvider.Factory {
        /** 创建当前页面模型，薄保存不含会话密钥、交易资格或DTO。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>,extras:CreationExtras):T=DealDetailViewModel(repository,extras.createSavedStateHandle()) as T
    }
}
