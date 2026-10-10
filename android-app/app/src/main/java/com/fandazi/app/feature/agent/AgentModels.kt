// 助手数据模型：限定公开网关的模式和界面状态。
package com.fandazi.app.feature.agent

import java.math.BigDecimal
import java.time.Instant
import java.io.Serializable

/** 只包含公开网关支持的模式，并默认要求用户确认。 */
enum class AgentMode { CONFIRM, THIS_TASK_AUTO }

enum class AgentStatus { COMPLETED, NEEDS_INPUT, AWAITING_CONFIRMATION, FAILED }

enum class AgentSheetMode { HIDDEN, HALF, FULL }

enum class AgentInputState { IDLE, RECORDING, RECOGNIZING }

/**
 * 表示聊天卡片要求的受控导航请求。
 *
 * 默认值为空；只有用户主动点击卡片上的受控操作后，后续任务才允许写入该状态。
 */
sealed interface AgentNavigationRequest : Serializable {
    /** 用户点击门店卡后，请求打开指定门店。 */
    data class Store(val storePublicId: String) : AgentNavigationRequest

    /** 用户点击套餐卡后，请求打开指定套餐。 */
    data class Deal(val storePublicId: String, val dealPublicId: String) : AgentNavigationRequest

    /** 用户主动点击后以服务端本人订单 ID 打开现有订单详情，不创建第二条路由。 */
    data class Order(val orderPublicId: String) : AgentNavigationRequest

    /** 只读券卡的显式点击打开该券详情；不发送退款确认或请求展示令牌。 */
    data class Voucher(val voucherPublicId: String) : AgentNavigationRequest

    /** 卡片动作必须携带原公开摘要；默认空来源仅便于拒绝不完整调用，不赋予任何执行权限。 */
    data class AgentReply(val text: String, val sourceCard: AgentCardPayload? = null) : AgentNavigationRequest
}

/** 一条消息及其服务端结果；历史卡片与进度固定在对应回复之后，不串到后续轮次。 */
data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val cards: List<AgentCardPayload> = emptyList(),
    val events: List<AgentEvent> = emptyList(),
    val streaming: Boolean = false,
    /** 首次创建后固定；历史从clientMessageKey或服务端trace恢复，不能用文案或列表位置生成。 */
    val displayKey: String = java.util.UUID.randomUUID().toString()
) : Serializable

data class AgentEvent(
    val id: Long?,
    val type: String,
    val summary: String,
    val toolName: String? = null,
    val status: String? = null,
    /** 事件附带的后端字段，例如工具进度或卡片引用；客户端不自行补造业务值。 */
    val payload: Map<String, String> = emptyMap()
) : Serializable {
    /** 原 Agent 事件身份不同于 id（数据库 SSE 游标）；HTTP 重放不推进数据库游标。 */
    val eventId: String? get() = payload["eventId"]
    val runId: String? get() = payload["runId"]
    val traceId: String? get() = payload["traceId"]
    val sequence: Long? get() = payload["sequence"]?.toLongOrNull()
}

/**
 * 后端返回的卡片引用与补全字段。
 *
 * Android 只保存后端返回的公开 ID 和展示字段，不在本地补造门店、套餐或订单数据。
 */
data class AgentCardPayload(
    val type: String,
    val storePublicId: String? = null,
    val dealPublicId: String? = null,
    val orderPublicId: String? = null,
    val fields: Map<String, String> = emptyMap(),
    /** 原待确认事件身份只用于客户端归属校验，不是授权凭证，不改变服务端工具参数。 */
    val confirmationIdentity: AgentConfirmationIdentity? = null,
    /** 历史卡永远只读；即使摘要未过期也不得因恢复可见而重新获得确认资格。 */
    val historicalReadOnly: Boolean = false
) : Serializable {
    /** 缺少任何服务端身份时才用于展示；创建一次，不纳入业务相等/授权判断或工具参数。 */
    val localDisplayKey: String = java.util.UUID.randomUUID().toString()
}

/** 同run重新准备仍产生新事件版本；trace与eventId共同约束原卡所属摘要，不以runId单独放行。 */
data class AgentConfirmationIdentity(val runId: String, val traceId: String, val eventId: String, val sequence: Long?) : Serializable

/** 展示引用仅用于指代，序号可省略表示关联整份列表，不携带候选数组或确认凭证。 */
data class AgentSelectionRef(val presentationId: String, val version: Int, val ordinal: Int? = null) : Serializable

/** 从服务端公开元数据建立引用；内容被重排/分页而序号不再连续时保守拒绝关联。 */
internal object AgentPresentationReferences {
    /** 单卡引用要求完整版本、类别和来源，损坏 JSON 不生成本地替代身份。 */
    fun card(card: AgentCardPayload): AgentSelectionRef? = runCatching {
        val value = com.google.gson.JsonParser.parseString(card.fields["presentation"] ?: return null).asJsonObject
        require(value.keySet() == setOf("presentationId", "version", "ordinal", "resourceType", "sourceMessageId"))
        fun text(key: String) = value.get(key).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun integer(key: String): Int {
            val number = value.get(key)
            require(number.isJsonPrimitive && number.asJsonPrimitive.isNumber && number.toString().matches(Regex("[1-9][0-9]*")))
            return requireNotNull(number.toString().toIntOrNull())
        }
        val type = if (card.type == "after_sale_voucher_choice") "voucher" else card.type
        require(text("resourceType") == type && !text("sourceMessageId").isNullOrBlank())
        AgentSelectionRef(requireNotNull(text("presentationId")).also { require(it.isNotBlank() && it.length <= 100) }, integer("version"), integer("ordinal"))
    }.getOrNull()

    /** 只关联唯一且原顺序完整的资源列表；两类列表或缺元数据时不能按最近一项猜。 */
    fun current(cards: List<AgentCardPayload>): AgentSelectionRef? {
        val resources = cards.filter { it.type in setOf("store", "deal", "order", "voucher", "coupon", "after_sale_voucher_choice") }
        if (resources.isEmpty()) return null
        val refs = resources.map { card(it) ?: return null }
        if (refs.map { it.presentationId to it.version }.distinct().size != 1 || refs.map { it.ordinal } != (1..refs.size).toList()) return null
        return refs.first().copy(ordinal = null)
    }
}

data class CandidateCartItem(
    val name: String,
    val specification: String = "",
    val price: String,
    val quantity: Int = 1
) : Serializable

data class CandidateCart(
    val merchantName: String,
    val items: List<CandidateCartItem>,
    val address: String,
    val deliveryFee: String,
    val discount: String,
    val total: String,
    val maxAmount: String,
    val addressId: Long,
    val authorizationMode: AgentMode? = null,
    val orderId: Long? = null
) : Serializable {
// 判断complete、confirmation。
    fun isCompleteForConfirmation() = merchantName.isNotBlank() && items.isNotEmpty() &&
        items.all { it.name.isNotBlank() && it.specification.isNotBlank() && it.price.isMoney() } &&
        address.isNotBlank() && deliveryFee.isMoney() && discount.isMoney() && total.isMoney() &&
        maxAmount.isMoney() && addressId > 0 && authorizationMode != null

    private fun String.isMoney() = isNotBlank() && this != "—" && replace(Regex("[^0-9.]"), "")
        .toBigDecimalOrNull()?.let { it >= BigDecimal.ZERO } == true
}

data class AgentMessageResult(
    val status: AgentStatus,
    val message: String,
    val nextAction: String?,
    val candidateCart: CandidateCart?,
    val events: List<AgentEvent>,
    val error: String? = null,
    /** 由后端补全后的卡片列表，字段只用于渲染和受控导航。 */
    val cards: List<AgentCardPayload> = emptyList()
)

data class OrderConfirmation(
    val merchantName: String,
    val items: List<CandidateCartItem>,
    val address: String,
    val deliveryFee: String,
    val discount: String,
    val total: String,
    val authorizationMode: AgentMode,
    val amountLimitText: String,
    val addressId: Long
) : Serializable

data class AgentAuthorization(
    val isTaskAuto: Boolean = false,
    val defaultAddress: String? = null,
    val maxAmount: String? = null
) : Serializable

sealed interface AgentError {
    data object Authentication : AgentError
    data class Server(val message: String) : AgentError
    data class Network(val message: String) : AgentError
    data class Asr(val message: String) : AgentError
}

data class AgentUiState(
    val sheetMode: AgentSheetMode = AgentSheetMode.HIDDEN,
    val composerText: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val events: List<AgentEvent> = emptyList(),
    /** 当前轮回答携带的卡片；页面刷新时仍以服务端返回为准。 */
    val cards: List<AgentCardPayload> = emptyList(),
    val candidateCart: CandidateCart? = null,
    val confirmation: OrderConfirmation? = null,
    val authorization: AgentAuthorization = AgentAuthorization(),
    val error: AgentError? = null,
    val lastEventId: Long? = 0L,
    val isSending: Boolean = false,
    val inputState: AgentInputState = AgentInputState.IDLE,
    val createdOrderId: Long? = null,
    val navigation: AgentNavigationRequest? = null,
    /** 用户最后明确查看的公开卡片，只用于历史摘要，不作为服务端授权或自动写入参数。 */
    val viewedCard: AgentCardPayload? = null,
    /** 仅真实waiting_confirmation期间保留当前版本；历史消息卡继续显示，但不能因此获得可执行状态。 */
    val pendingConfirmationCard: AgentCardPayload? = null,
    /** 当前售后待输入候选或待确认摘要；历史卡片另存消息，不能凭历史可见性继续执行。 */
    val pendingAfterSaleCards: List<AgentCardPayload> = emptyList(),
    /** 服务端历史完整应用之前阻止发送，恢复失败只能重试只读GET。 */
    val isRecoveringHistory: Boolean = false,
    /** 原消息表没有保存内容时如实提示，不根据事件反造用户话语。 */
    val historyNotice: String? = null,
    /** 稳定消息锚点与像素偏移；由聊天列表读取/更新，不使用绝对列表位置作身份。 */
    val scrollAnchorKey: String? = null,
    val scrollAnchorOffset: Int = 0
)

data class ThisTaskAutoRequest(
    val sessionId: String,
    val addressId: Long,
    val maxAmount: BigDecimal,
    val expiresAt: Instant
)
