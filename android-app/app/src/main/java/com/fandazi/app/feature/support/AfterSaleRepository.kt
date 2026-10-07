// 售后数据层：统一调用 Spring 接口，供退款、评价、帮助和投诉页面复用真实响应。
package com.fandazi.app.feature.support

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

/** 帮助条目完全由服务端维护，页面不缓存或写死规则正文。 */
data class FaqItem(val id: String, val question: String, val answer: String)

/** 公开分类或投诉类型目录：编号和标签均取后端，端上只负责选中状态与图标展示。 */
data class SupportOption(val id: String, val label: String)

/** 退款预览金额和券信息由服务端计算；一次性凭证只保存在当前提交链路内。 */
data class RefundPreparation(
    val voucherId: String,
    val orderId: String,
    val dealTitle: String,
    val amountCents: Int,
    val expiresAt: String,
    val confirmationGrant: String,
    // 资格和摘要以本次 prepare 为准，禁止由客户端券状态拼装确认内容。
    val canRefund: Boolean,
    val storeName: String,
    val imageUrl: String?,
    val imageAlt: String?
)

/** 退款进度只反映服务端记录的状态，不推断审批或到账时间。 */
data class RefundProgress(
    val requestId: String,
    val voucherId: String,
    val status: String,
    val reason: String,
    val createdAt: String,
    // 进度查询自带申请快照和资源摘要，重新进入页面不依赖上次页面缓存。
    val orderId: String,
    val dealTitle: String,
    val storeName: String,
    val amountCents: Int,
    val imageUrl: String?,
    val imageAlt: String?
)

/** 投诉提交成功后仅展示真实工单编号与当前状态。 */
data class ComplaintReceipt(val ticketId: String, val status: String, val subject: String)

/** 将售后读写操作与页面分离，方便页面对网络错误实施原地重试。 */
interface AfterSaleRepository {
    /** 分类与关键词在后端取交集，空分类表示全部公开帮助。 */
    suspend fun listFaqs(keyword: String = "", category: String? = null): ApiResult<List<FaqItem>>
    /** 读取分类目录，失败时交由页面原地重试，不补本地业务目录。 */
    suspend fun faqCategories(): ApiResult<List<SupportOption>>
    /** 读取投诉类型目录，未取得目录前不允许提交。 */
    suspend fun complaintTypes(): ApiResult<List<SupportOption>>
    /** 校验本人券并准备短时确认摘要；失败不生成客户端金额或资格。 */
    suspend fun prepareRefund(sessionId: String, voucherId: String): ApiResult<RefundPreparation>
    /** 用户明确确认后提交同一会话凭证和原因；返回真实申请，错误不自动重放。 */
    suspend fun submitRefund(sessionId: String, voucherId: String, reason: String, grant: String): ApiResult<RefundProgress>
    /** 根据公开申请编号读取本人当前进度，保留权限和不存在错误。 */
    suspend fun getRefund(requestId: String): ApiResult<RefundProgress>
    /** 本人按券查询已有单一申请，404 表示尚无申请；不推断退款状态或构造列表。 */
    suspend fun getRefundByVoucher(voucherId: String): ApiResult<RefundProgress>
    /** 发布本人订单的评分正文，重复性和资格由后端校验。 */
    suspend fun publishReview(orderId: String, rating: Int, content: String): ApiResult<Unit>
    /** 校验可选本人订单并准备确认凭证，此步骤不会建立投诉工单。 */
    suspend fun prepareComplaint(sessionId: String, orderId: String?): ApiResult<String>
    /** 显式确认后提交主题与说明，返回工单真实状态；凭证不落盘或显示。 */
    suspend fun submitComplaint(sessionId: String, orderId: String?, subject: String, description: String, grant: String): ApiResult<ComplaintReceipt>
}

/** 使用应用统一的认证客户端访问售后 API；不会把确认凭证写入磁盘或日志。 */
class HttpAfterSaleRepository(private val api: ApiClient, private val gson: Gson = Gson()) : AfterSaleRepository {
    /** 搜索条件在 URL 编码后提交，避免中文或空格破坏查询参数。 */
    override suspend fun listFaqs(keyword: String, category: String?): ApiResult<List<FaqItem>> =
        api.get("faqs?keyword=${keyword.encoded()}" + category?.let { "&category=${it.encoded()}" }.orEmpty()).parse { body ->
            JsonParser.parseString(body).asJsonArray.map { value ->
                val item = value.asJsonObject
                FaqItem(item.string("id"), item.string("question"), item.string("answer"))
            }
        }

    /** 分类和投诉目录使用同一严格解析结构，未知响应不得变成空目录假成功。 */
    override suspend fun faqCategories(): ApiResult<List<SupportOption>> = api.get("faqs/categories").parse { it.options() }

    /** 获取真实可选类型，标签更新无需重新发布 APK。 */
    override suspend fun complaintTypes(): ApiResult<List<SupportOption>> = api.get("complaints/types").parse { it.options() }

    /** 每项必须有非空 ID 和标签；重复编号会造成选中歧义，因此按协议错误拒绝。 */
    private fun String.options(): List<SupportOption> {
        val options = JsonParser.parseString(this).asJsonArray.map { value ->
            value.asJsonObject.run { SupportOption(string("id"), string("label")) }
        }
        require(options.all { it.id.isNotBlank() && it.label.isNotBlank() } && options.map { it.id }.distinct().size == options.size)
        return options
    }

    /** 先由服务端重新校验券归属和资格，再获得与本次操作绑定的短时凭证。 */
    override suspend fun prepareRefund(sessionId: String, voucherId: String): ApiResult<RefundPreparation> =
        api.postJson("refunds/prepare", gson.toJson(mapOf("sessionId" to sessionId, "voucherId" to voucherId)))
            .parse { body ->
                JsonParser.parseString(body).asJsonObject.run {
                    RefundPreparation(string("voucherId"), string("orderId"), string("dealTitle"),
                        get("amountCents").asInt, string("expiresAt"), string("confirmationGrant"),
                        get("canRefund").asBoolean, string("storeName"), nullableString("imageUrl"), nullableString("imageAlt"))
                }
            }

    /** 只在用户确认后写入；超时不会自动重试，避免创建重复申请。 */
    override suspend fun submitRefund(sessionId: String, voucherId: String, reason: String, grant: String): ApiResult<RefundProgress> =
        api.postJson("refunds", gson.toJson(mapOf("sessionId" to sessionId, "voucherId" to voucherId,
            "reason" to reason, "confirmationGrant" to grant))).parse { it.toRefundProgress() }

    /** 进度页重新进入时必须再次请求服务端，不能沿用本地想象的处理状态。 */
    override suspend fun getRefund(requestId: String): ApiResult<RefundProgress> =
        api.get("refunds/${requestId.encoded()}").parse { it.toRefundProgress() }

    /** 已申请券在重进或进程重启后按真实资源恢复进度，无须本地保存申请状态。 */
    override suspend fun getRefundByVoucher(voucherId: String): ApiResult<RefundProgress> =
        api.get("refunds/by-voucher/${voucherId.encoded()}").parse { it.toRefundProgress() }

    /** 后端再次验证核销资格与重复评价，成功只表示服务端已接受。 */
    override suspend fun publishReview(orderId: String, rating: Int, content: String): ApiResult<Unit> =
        api.postJson("group-reviews", gson.toJson(mapOf("orderId" to orderId, "rating" to rating,
            "content" to content))).parse { Unit }

    /** 投诉的可选订单仍须由后端验证归属；空订单代表一般反馈。 */
    override suspend fun prepareComplaint(sessionId: String, orderId: String?): ApiResult<String> =
        api.postJson("complaints/prepare", gson.toJson(mapOf("sessionId" to sessionId, "orderId" to orderId)))
            .parse { JsonParser.parseString(it).asJsonObject.string("confirmationGrant") }

    /** 确认凭证与同一会话、订单及动作绑定，提交失败时由页面重新准备而非盲目重放。 */
    override suspend fun submitComplaint(sessionId: String, orderId: String?, subject: String, description: String,
                                         grant: String): ApiResult<ComplaintReceipt> =
        api.postJson("complaints", gson.toJson(mapOf("sessionId" to sessionId, "orderId" to orderId,
            "subject" to subject, "description" to description, "confirmationGrant" to grant))).parse { body ->
            JsonParser.parseString(body).asJsonObject.run {
                ComplaintReceipt(string("ticketId"), string("status"), string("subject"))
            }
        }

    /** 只编码动态路径或参数，固定 API 路由保持原样。 */
    private fun String.encoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    /** 缺字段属于协议错误，应交给 UI 显示重试，不能填充虚构业务数据。 */
    private fun JsonObject.string(field: String): String = get(field)?.takeUnless { it.isJsonNull }?.asString
        ?: error("售后响应缺少字段：$field")

    /** 可空图片字段忠实保留缺图事实，页面使用统一占位图。 */
    private fun JsonObject.nullableString(field: String): String? = get(field)?.takeUnless { it.isJsonNull }?.asString

    /** 退款提交与详情接口使用相同字段，保持唯一解析入口。 */
    private fun String.toRefundProgress(): RefundProgress = JsonParser.parseString(this).asJsonObject.run {
        RefundProgress(string("requestId"), string("voucherId"), string("status"), string("reason"), string("createdAt"),
            string("orderId"), string("dealTitle"), string("storeName"), get("amountCents").asInt,
            nullableString("imageUrl"), nullableString("imageAlt"))
    }

    /** 保留 HTTP 错误；只把解析异常转换为不含响应正文的协议错误。 */
    private inline fun <T> ApiResult<String>.parse(parser: (String) -> T): ApiResult<T> = when (this) {
        is ApiResult.Success -> runCatching { ApiResult.Success(parser(value)) }.getOrElse {
            ApiResult.Failure(ApiError.Network("售后响应格式无效"))
        }
        is ApiResult.Failure -> this
    }
}
