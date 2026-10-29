// 秒杀用户数据边界：复用统一认证HTTP及购买解析器，所有业务字段来自后端且不使用目录缓存授权。
package com.fandazi.app.feature.seckill

import android.os.SystemClock
import com.fandazi.app.core.network.*
import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.feature.deal.DealPackagePayloadParser
import com.fandazi.app.feature.purchase.*
import com.google.gson.*
import java.time.Instant
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 公开活动完整快照；时间和库存不可由页面展示文字或本地日历推算。 */
data class SeckillActivity(val id:String,val version:Long,val state:String,val serverTime:Instant,
    val startsAt:Instant,val endsAt:Instant,val available:Int,val total:Int,val limit:Int,val deal:DealPackage)
/** 本人资格仅在当前认证会话有效，活动版本变化后须重新读取展示事实。 */
data class SeckillEligibility(val activityId:String,val version:Long,val state:String,val serverTime:Instant,
    val startsAt:Instant,val endsAt:Instant,val available:Int,val remaining:Int,val canPurchase:Boolean,val reason:String)
/** 报价的活动身份与版本必须匹配当前页面；有效授权只保留内存，不写恢复文件。 */
data class SeckillQuote(val value:PurchaseQuote,val activityId:String,val version:Long,val serverTime:Instant)
/** 支付截止与券期限分别读取；订单关闭以服务端终态为准，端上归零不冒称库存已经释放。 */
data class SeckillOrder(val value:GroupBuyOrder,val activityId:String,val paymentExpiresAt:Instant,val serverTime:Instant,
    val closedAt:Instant?,val closeReason:String?)
/** 请求起止只取设备单调时钟，为一次成功响应建立时间锚点；不保存访问凭据。 */
data class SeckillTimed<T>(val value:T,val started:Long,val received:Long)

/** 页面专用边界；所有写操作均由明确点击调用，读取原回执不产生订单。 */
interface SeckillRepository {
    /** 按公开门店身份读取活动列表；游客可读，返回权威时间和请求时延，不写库存。 */
    suspend fun list(storeId:String):ApiResult<SeckillTimed<List<SeckillActivity>>>
    /** 读取指定活动完整事实；失败保留错误，不用其他活动替代。 */
    suspend fun detail(activityId:String):ApiResult<SeckillTimed<SeckillActivity>>
    /** 使用当前HTTP认证读取本人剩余额度；身份切换后调用方必须丢弃旧响应。 */
    suspend fun eligibility(activityId:String):ApiResult<SeckillTimed<SeckillEligibility>>
    /** 为明确选择的数量准备绑定会话的报价；只签确认，不预占库存。 */
    suspend fun quote(dealId:String,quantity:Int,sessionId:String):ApiResult<SeckillTimed<SeckillQuote>>
    /** 提交已展示报价；调用方必须先持久保存requestId，失败不自动更换请求键。 */
    suspend fun create(quote:SeckillQuote,sessionId:String,requestId:String):ApiResult<SeckillTimed<SeckillOrder>>
    /** 只读本人原订单；公开订单身份不能代替服务端所有权校验。 */
    suspend fun order(orderId:String):ApiResult<SeckillTimed<SeckillOrder>>
    /** 用户明确点击后模拟支付；保留稳定请求身份，超时只能先核查原订单。 */
    suspend fun pay(orderId:String,requestId:String):ApiResult<SeckillTimed<SeckillOrder>>
    /** 成功且value为空表示后端明确RESULT_UNKNOWN，仍不得推断为未创建。 */
    suspend fun original(requestId:String):ApiResult<SeckillTimed<SeckillOrder?>>
}

/** 仅组装已有用户路径；格式异常封闭为未知/网络错误，写请求始终由ApiClient禁止自动重试。 */
class HttpSeckillRepository(private val api:ApiClient,private val elapsed:()->Long=SystemClock::elapsedRealtime):SeckillRepository {
    private val gson=Gson()
    /** 预告列表允许库存零，列表顺序由后端决定。 */
    override suspend fun list(storeId:String)=read("stores/${encode(storeId)}/seckill-activities") { body->
        JsonParser.parseString(body).asJsonArray.map { SeckillPayload.activity(it.asJsonObject) }
    }
    /** 详情读取包括停止或已结束活动，供订单返回查看。 */
    override suspend fun detail(activityId:String)=read("seckill-activities/${encode(activityId)}") { SeckillPayload.activity(JsonParser.parseString(it).asJsonObject) }
    /** 本人额度不可与另一个登录会话共享；调用方须核对发包和回包身份。 */
    override suspend fun eligibility(activityId:String)=read("seckill-activities/${encode(activityId)}/eligibility",SeckillPayload::eligibility)
    /** 数量变化只签发新报价，永不预留库存。 */
    override suspend fun quote(dealId:String,quantity:Int,sessionId:String)=write("purchases/quotes",
        mapOf("dealId" to dealId,"quantity" to quantity,"sessionId" to sessionId),SeckillPayload::quote)
    /** 提交当前已展示报价；原请求键必须先持久化，再进入此网络调用。 */
    override suspend fun create(quote:SeckillQuote,sessionId:String,requestId:String)=write("group-orders",
        mapOf("quoteId" to quote.value.quoteId,"confirmationGrant" to quote.value.confirmationGrant,"sessionId" to sessionId,"idempotencyKey" to requestId),SeckillPayload::order)
    /** 恢复已有订单只查本人事实，不创建新订单。 */
    override suspend fun order(orderId:String)=read("group-orders/${encode(orderId)}") {body->
        SeckillPayload.order(body).also {require(it.value.orderId==orderId)}
    }
    /** 模拟支付仍是独立用户动作；不复用购买确认，也不连接真实支付渠道。 */
    override suspend fun pay(orderId:String,requestId:String)=write("group-orders/${encode(orderId)}/mock-pay",mapOf("idempotencyKey" to requestId)) {body->
        SeckillPayload.order(body).also {require(it.value.orderId==orderId)}
    }
    /** 丢响应后只使用原键核查；不存在记录不等于原写入一定未提交。 */
    override suspend fun original(requestId:String)=read("seckill-purchases/requests/${encode(requestId)}") { body->
        val value=JsonParser.parseString(body).asJsonObject
        when(value.get("state").asString){"FOUND"->SeckillPayload.order(value.getAsJsonObject("order").toString());"RESULT_UNKNOWN"->null;else->error("未知回执状态")}
    }
    /** 路径编码不允许公开资源标识改变接口路由。 */
    private fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8.name())
    /** 统一读取与解析，不把格式失败包装为空活动或默认库存。 */
    private suspend fun <T> read(path:String,parser:(String)->T)=timed({api.get(path)},parser)
    /** 序列化只有公开身份、报价授权及原键，不能接受端上金额或库存。 */
    private suspend fun <T> write(path:String,payload:Map<String,Any>,parser:(String)->T)=timed({api.postJson(path,gson.toJson(payload))},parser)
    /** 后台转换JSON并保留网络起止；取消原样抛出，不把页面离开解释成服务端回滚。 */
    private suspend fun <T> timed(request:suspend ()->ApiResult<String>,parser:(String)->T):ApiResult<SeckillTimed<T>> {
        val start=elapsed()
        return when(val result=request()){
            is ApiResult.Failure->result
            is ApiResult.Success->try{
                val received=elapsed();val value=withContext(Dispatchers.Default){parser(result.value)}
                ApiResult.Success(SeckillTimed(value,start,received))
            }catch(cancelled:CancellationException){throw cancelled}
            catch(invalid:Exception){ApiResult.Failure(ApiError.Network("秒杀数据未完整返回，请重新核查"))}
        }
    }
}

/** 必需金额、身份、时间、整数和枚举均拒绝缺失；不以空白或零值掩盖协议错误。 */
object SeckillPayload {
    /** 活动与专属套餐必须为同一对象，库存守恒范围和窗口非法时拒绝展示为可购买。 */
    fun activity(r:JsonObject):SeckillActivity {
        val deal=DealPackagePayloadParser.parseDetail(r.getAsJsonObject("deal"))
        require(r.text("dealId")==deal.publicId)
        return SeckillActivity(r.text("activityId"),r.number("activityVersion"),r.text("state"),r.instant("serverTime"),
            r.instant("startsAt"),r.instant("endsAt"),r.int("availableStock"),r.int("totalStock"),r.int("perUserLimit"),deal).also {
            require(it.version>=0 && it.state in setOf("UPCOMING","LIVE","ENDED","STOPPED"))
            require(it.startsAt<it.endsAt && it.total>0 && it.available in 0..it.total && it.limit in 1..99)
        }
    }
    /** 本人资格不能用字符串true或缺失布尔替代；可购必须同时满足活动/库存/额度。 */
    fun eligibility(body:String):SeckillEligibility {
        val r=JsonParser.parseString(body).asJsonObject;require(r["canPurchase"].asJsonPrimitive.isBoolean)
        return SeckillEligibility(r.text("activityId"),r.number("activityVersion"),r.text("state"),r.instant("serverTime"),
            r.instant("startsAt"),r.instant("endsAt"),r.int("availableStock"),r.int("remainingQuota"),r["canPurchase"].asBoolean,r.text("reasonCode")).also {
            require(it.version>=0 && it.state in setOf("UPCOMING","LIVE","ENDED","STOPPED"))
            require(it.reason in setOf("AVAILABLE","NOT_STARTED","ENDED","STOPPED","SOLD_OUT","LIMIT_REACHED"))
            require(it.available>=0 && it.remaining>=0 && it.startsAt<it.endsAt)
            require(!it.canPurchase || (it.state=="LIVE" && it.available>0 && it.remaining>0 && it.reason=="AVAILABLE"))
        }
    }
    /** 复用已验证购买金额与数量解析，只增加不可缺省的秒杀身份及时间。 */
    fun quote(body:String):SeckillQuote {
        val r=JsonParser.parseString(body).asJsonObject
        return SeckillQuote(PurchasePayload.quote(body),r.text("activityId"),r.number("activityVersion"),r.instant("serverTime"))
    }
    /** 普通订单不能误入秒杀支付页；支付截止与关闭事实必须明确可解析。 */
    fun order(body:String):SeckillOrder {
        val r=JsonParser.parseString(body).asJsonObject
        return SeckillOrder(PurchasePayload.order(body),r.text("activityId"),r.instant("paymentExpiresAt"),r.instant("serverTime"),
            r.optional("closedAt")?.let(Instant::parse),r.optional("closeReason")).also {
            require(it.value.status in setOf("PENDING_PAYMENT","PAID","PARTIALLY_USED","USED","REFUNDED","EXPIRED","CLOSED_TIMEOUT"))
            require(it.value.status!="CLOSED_TIMEOUT" || (it.closedAt!=null && it.closeReason=="PAYMENT_EXPIRED"))
        }
    }
    /** 可选关闭字段允许null，其他字段缺失一律由text拒绝。 */
    private fun JsonObject.optional(key:String)=get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString);it.asString
    }
    /** 必需文本不接受空白；避免缺身份导航到默认对象。 */
    private fun JsonObject.text(key:String)=requireNotNull(optional(key)?.takeIf(String::isNotBlank))
    /** 时间必须含时区且是有效UTC瞬时值。 */
    private fun JsonObject.instant(key:String)=Instant.parse(text(key))
    /** 版本不截断小数或溢出长整数。 */
    private fun JsonObject.number(key:String)=get(key).let {
        require(it!=null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber);it.asBigDecimal.longValueExact()
    }
    /** 库存和额度使用精确整数，不接受小数和溢出。 */
    private fun JsonObject.int(key:String)=get(key).let {
        require(it!=null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber);it.asBigDecimal.intValueExact()
    }
}
