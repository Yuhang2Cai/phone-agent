// 秒杀交互状态边界：协调权威读取、明确确认及未知结果恢复；不依赖Android控件。
package com.fandazi.app.feature.seckill

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 恢复只保存公开对象和原请求键，绝不保存报价授权、金额或访问凭据。 */
data class SeckillRecovery(val activityId:String,val requestId:String,val orderId:String?=null)
/** 本人恢复记录必须按账号隔离；保存失败不得发起写请求。 */
interface SeckillRecoveryStore {
    /** 读取当前账号的原写入；存储损坏由调用方封闭处理。 */
    suspend fun load(account:String):SeckillRecovery?
    /** 新原请求须原子落盘；回填订单时提供expectedRequestId，只更新仍匹配的原键，防止覆盖后来写入。 */
    suspend fun save(account:String,value:SeckillRecovery,expectedRequestId:String?=null):Boolean
    /** 仅清理与原请求键相符的已解决记录；不存在或已换成另一原键时成功返回且不删除新记录。 */
    suspend fun clear(account:String,requestId:String):Boolean
}
/** 展示事实与操作能力分离；忙、断网、未知结果都不具备提交能力。 */
data class SeckillState(val activity:SeckillActivity?=null,val eligibility:SeckillEligibility?=null,
    val quote:SeckillQuote?=null,val order:SeckillOrder?=null,val busy:Boolean=false,
    val synchronized:Boolean=false,val unknown:Boolean=false,val error:ApiError?=null)

/** 同一页面使用单个流程实例；身份切换撤销旧响应，恢复过程只有读取。 */
class SeckillFlow(private val repository:SeckillRepository,private val recovery:SeckillRecoveryStore,
    private val elapsed:()->Long,private val newKey:()->String) {
    private val mutable=MutableStateFlow(SeckillState())
    /** 只读状态供XML页面观察，不能由页面直接赋造业务事实。 */
    val state=mutable.asStateFlow()
    /** 服务器时间锚点只负责倒计时显示。 */
    val clock=SeckillClock()
    private var generation=0L
    private var activityId=""
    private var account:String?=null
    private var session:String?=null
    private var pending:SeckillRecovery?=null
    private var quoteSession=""
    private var recoveryBlocked=false
    /** 接入身份后先读取原请求；原请求属于另一活动时恢复原活动，禁止绕过未知写入再下单。 */
    suspend fun open(activityId:String,account:String?,session:String?,orderId:String?=null) {
        val epoch=++generation
        this.activityId=activityId;this.account=account;this.session=session;pending=null;recoveryBlocked=true
        mutable.value=SeckillState(busy=true)
        try {
            val saved=account?.let { recovery.load(it) }
            if(epoch!=generation)return
            recoveryBlocked=false
            // 未知写入优先恢复；已知原订单则允许用户从订单列表明确选中另一笔本人订单。
            // 显式订单恢复只读，合成恢复键不会发送到创建接口，也不作为新的交易身份。
            // 已知订单属于另一活动时不替换用户目标；它仍能通过订单列表显式恢复。
            // 只有尚不确定是否建单的原请求需要跨活动优先核查，避免另起请求造成重复业务事实。
            val selected=if(saved!=null && saved.orderId==null)saved else if(account!=null && !orderId.isNullOrBlank())SeckillRecovery(activityId,"read-order:$orderId",orderId) else saved?.takeIf {it.activityId==activityId}
            pending=selected;this.activityId=selected?.activityId ?: activityId
            mutable.value=SeckillState(unknown=selected!=null && selected.orderId==null)
            refresh()
        } catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){if(epoch==generation)mutable.value=SeckillState(error=ApiError.Storage)}
    }
    /** 只读取活动或原回执，不允许在刷新中写订单。 */
    suspend fun refresh() {
        // 读取原请求失败不能靠刷新绕过；先成功重建同一账号恢复上下文，再允许查询活动。
        if(recoveryBlocked){fail(ApiError.Storage);return}
        if(mutable.value.busy || activityId.isBlank())return
        val epoch=generation
        mutable.value=mutable.value.copy(busy=true,error=null)
        try {
            // 已有原写入只查回执或已知订单；正常轮询不会重发create/pay。
            val original=pending
            if(original!=null) {
                val result=if(original.orderId==null)repository.original(original.requestId) else repository.order(original.orderId)
                if(epoch!=generation)return
                when(result) {
                    is ApiResult.Failure->fail(result.error)
                    is ApiResult.Success->{
                        val order=result.value.value
                        if(order==null)mutable.value=mutable.value.copy(unknown=true,quote=null)
                        else acceptOrder(order,result.value.started,result.value.received,epoch)
                    }
                }
                // 订单恢复之后加载公开展示信息，失败不抹去已核实的订单事实。
                if(epoch==generation && mutable.value.activity==null)readActivity(epoch)
                return
            }
            if((mutable.value.activity==null || account==null || session==null) && !readActivity(epoch))return
            if(epoch!=generation)return
            if(account==null || session==null) {
                // 游客只读公开快照，不伪造“本人可买”资格。
                if(mutable.value.activity!=null)mutable.value=mutable.value.copy(synchronized=true)
                return
            }
            when(val result=repository.eligibility(activityId)) {
                is ApiResult.Failure->if(epoch==generation)fail(result.error)
                is ApiResult.Success->{
                    if(epoch!=generation)return
                    val data=result.value.value
                    require(data.activityId==activityId)
                    // 活动改版会使已展示报价失效；先刷新商品事实，不能只换按钮继续提交旧报价。
                    if(data.version!=mutable.value.activity?.version) {
                        mutable.value=mutable.value.copy(quote=null)
                        if(!readActivity(epoch))return
                        require(data.version==mutable.value.activity?.version)
                    }
                    anchor(data.serverTime,result.value.started,result.value.received)
                    mutable.value=mutable.value.copy(eligibility=data,synchronized=true)
                }
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(invalid:Exception){if(epoch==generation)fail(ApiError.Network("活动状态需要重新核对"))}
        finally {if(epoch==generation)mutable.value=mutable.value.copy(busy=false)}
    }
    /** 逐秒调用只推进本地显示，归零撤销可购资格。 */
    fun tick() {
        val current=mutable.value
        val time=clock.now(elapsed())
        val order=current.order
        val eligibility=current.eligibility
        val activity=current.activity
        // 时间达到边界只能撤销授权。是否开始、关闭或已支付必须依赖下一次后端结果。
        val boundary=when {
            order?.value?.status=="PENDING_PAYMENT"->time==null || !time.isBefore(order.paymentExpiresAt)
            order!=null->false
            activity==null->true
            (eligibility?.state ?: activity.state)=="UPCOMING"->time==null || !time.isBefore(activity.startsAt)
            (eligibility?.state ?: activity.state)=="LIVE"->time==null || !time.isBefore(activity.endsAt)
            else->false
        }
        val quoteExpired=current.quote?.let {time==null || !time.isBefore(Instant.parse(it.value.expiresAt))} ?: false
        if(boundary || quoteExpired)mutable.value=current.copy(synchronized=if(boundary)false else current.synchronized,quote=if(quoteExpired)null else current.quote)
    }
    /** 明确点击选数量后准备报价，不预占库存。 */
    suspend fun prepare(quantity:Int) {
        tick()
        val current=mutable.value;val activity=current.activity ?: return;val eligible=current.eligibility ?: return
        if(current.busy || !current.synchronized || current.unknown || current.order!=null || pending!=null || !eligible.canPurchase || session==null)return
        if(quantity !in 1..minOf(eligible.available,eligible.remaining,activity.limit))return
        val epoch=generation;quoteSession="seckill-${newKey()}"
        mutable.value=current.copy(busy=true,quote=null,error=null)
        try {
            when(val result=repository.quote(activity.deal.publicId,quantity,quoteSession)) {
                is ApiResult.Failure->if(epoch==generation)fail(result.error)
                is ApiResult.Success->{
                    if(epoch!=generation)return
                    val value=result.value.value
                    require(value.activityId==activity.id && value.version==activity.version && value.value.dealId==activity.deal.publicId && value.value.quantity==quantity)
                    anchor(value.serverTime,result.value.started,result.value.received)
                    require(clock.now(elapsed())!!.isBefore(Instant.parse(value.value.expiresAt)))
                    mutable.value=mutable.value.copy(quote=value,synchronized=true)
                }
            }
        }catch(cancelled:CancellationException){throw cancelled}
        catch(invalid:Exception){if(epoch==generation)fail(ApiError.Network("报价已变化，请重新确认"))}
        finally {if(epoch==generation)mutable.value=mutable.value.copy(busy=false)}
    }
    /** 用户确认当前报价后提交一次。 */
    suspend fun confirm() {
        tick()
        val current=mutable.value;val quote=current.quote ?: return;val owner=account ?: return
        if(current.busy || !current.synchronized || current.unknown || current.order!=null || pending!=null)return
        val epoch=generation;val saved=SeckillRecovery(activityId,newKey())
        mutable.value=current.copy(busy=true,error=null)
        try {
            // 落盘在POST之前完成。失败时不能形成无法追踪的写入；成功后任何取消都按未知处理。
            // 先在内存封闭重入窗口；即使落盘完成后的协程恢复被取消，也不会丢掉原键而生成第二键。
            pending=saved;mutable.value=mutable.value.copy(unknown=true,quote=null,synchronized=false)
            if(!recovery.save(owner,saved)){
                if(epoch==generation){pending=null;mutable.value=mutable.value.copy(unknown=false);fail(ApiError.Storage)}
                return
            }
            if(epoch!=generation)return
            when(val result=repository.create(quote,quoteSession,saved.requestId)) {
                is ApiResult.Success->if(epoch==generation)acceptOrder(result.value.value,result.value.started,result.value.received,epoch)
                is ApiResult.Failure->if(epoch==generation){
                    val http=result.error as? ApiError.Http
                    // 只有稳定的业务拒绝才能结束原写入；401、5xx、超时和格式错误仍需原键回查。
                    if(http?.code in 400..499 && http?.reasonCode in setOf("NOT_STARTED","ENDED","STOPPED","SOLD_OUT","LIMIT_REACHED","ACTIVITY_CHANGED","QUOTE_EXPIRED","AMOUNT_EXCEEDED","INVALID_QUANTITY")) {
                        val cleared=recovery.clear(owner,saved.requestId)
                        // 清理是可挂起的磁盘操作；恢复后再次核对代次，旧账号的拒绝不能污染新身份。
                        if(epoch!=generation)return
                        if(cleared){pending=null;mutable.value=mutable.value.copy(unknown=false)}
                    }
                    fail(result.error)
                }
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(invalid:Exception){if(epoch==generation)fail(ApiError.Network("提交结果待核查，请查询本次结果"))}
        finally {if(epoch==generation)mutable.value=mutable.value.copy(busy=false)}
    }
    /** 支付也要求独立点击和权威待支付状态。 */
    suspend fun pay() {
        tick();val current=mutable.value;val order=current.order ?: return
        if(current.busy || !current.synchronized || current.unknown || order.value.status!="PENDING_PAYMENT" || account==null)return
        val epoch=generation
        mutable.value=current.copy(busy=true,synchronized=false,error=null)
        try {
            // 支付使用稳定订单键；丢响应后只读订单直到核实，不能在恢复生命周期里再次支付。
            when(val result=repository.pay(order.value.orderId,"seckill-pay-${order.value.orderId}")) {
                is ApiResult.Success->if(epoch==generation)acceptOrder(result.value.value,result.value.started,result.value.received,epoch)
                is ApiResult.Failure->if(epoch==generation)fail(result.error)
            }
        }catch(cancelled:CancellationException){throw cancelled}
        catch(invalid:Exception){if(epoch==generation)fail(ApiError.Network("支付结果待核查，请刷新订单"))}
        finally {if(epoch==generation)mutable.value=mutable.value.copy(busy=false)}
    }
    /** 离开确认面板时销毁内存中的授权。 */
    fun dismissQuote() {if(!mutable.value.busy)mutable.value=mutable.value.copy(quote=null)}
    /** 只有已知终态允许返回活动发起新的明确购买；待支付和未知写入必须先处理。 */
    suspend fun returnToActivity() {
        val current=mutable.value;val owner=account ?: return
        if(current.busy || current.unknown || current.order==null || current.order.value.status=="PENDING_PAYMENT")return
        val original=pending ?: return
        val epoch=generation
        // 返回动作同样先封闭重入；旧记录清理完成前不能启动第二次返回或交易。
        mutable.value=current.copy(busy=true)
        try {
            val cleared=recovery.clear(owner,original.requestId)
            // 清理成功、失败均属于原账号；切账号或切券后只丢弃结果，不覆盖新页面或重新刷新旧活动。
            if(epoch!=generation)return
            if(!cleared){fail(ApiError.Storage);return}
            pending=null;mutable.value=SeckillState(activity=current.activity);refresh()
        }catch(cancelled:CancellationException){throw cancelled}
        catch(storage:Exception){if(epoch==generation)fail(ApiError.Storage)}
        finally {if(epoch==generation)mutable.value=mutable.value.copy(busy=false)}
    }
    /** 公开详情校验身份后才能更新；旧账号请求即使成功也不写当前状态。 */
    private suspend fun readActivity(epoch:Long):Boolean {
        return when(val result=repository.detail(activityId)) {
            is ApiResult.Failure->{if(epoch==generation)fail(result.error);false}
            is ApiResult.Success->{
                if(epoch!=generation)return false
                require(result.value.value.id==activityId)
                if(mutable.value.order==null)anchor(result.value.value.serverTime,result.value.started,result.value.received)
                mutable.value=mutable.value.copy(activity=result.value.value)
                true
            }
        }
    }
    /** 保存已核实的订单身份供进程恢复；原记录仍可通过请求键找回，不保存授权和业务金额。 */
    private suspend fun acceptOrder(order:SeckillOrder,started:Long,received:Long,epoch:Long) {
        require(order.activityId==activityId)
        require(mutable.value.order?.value?.orderId?.let {it==order.value.orderId} ?: true)
        val saved=pending?.copy(orderId=order.value.orderId)
        if(saved!=null && account!=null) {
            val owner=account!!
            // 已核实终态已解除未知写入风险；页面继续显示本订单，但下次进入活动须重读资格。
            // 待支付仍保存原订单，恢复时不能重置支付截止或新建订单。
            // 已知订单回填只是补全原记录，不能覆盖另一个页面新保存的未知请求。
            if(order.value.status=="PENDING_PAYMENT")recovery.save(owner,saved,saved.requestId) else recovery.clear(owner,saved.requestId)
            if(epoch!=generation)return
            pending=saved
        }
        anchor(order.serverTime,started,received)
        mutable.value=mutable.value.copy(order=order,quote=null,unknown=false,synchronized=true,error=null)
        tick()
    }
    /** 只有合法服务器锚点才能恢复可操作状态；非法往返交由调用边界封闭。 */
    private fun anchor(time:Instant,started:Long,received:Long)=clock.synchronize(time,started,received)
    /** 错误不会清掉原写入或订单事实，但撤销当前确认资格。 */
    private fun fail(error:ApiError) {mutable.value=mutable.value.copy(error=error,synchronized=false,quote=null)}
}
