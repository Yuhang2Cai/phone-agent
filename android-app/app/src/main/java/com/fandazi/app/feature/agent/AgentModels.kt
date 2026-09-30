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
}

data class ChatMessage(val text: String, val fromUser: Boolean) : Serializable

data class AgentEvent(
    val id: Long?,
    val type: String,
    val summary: String,
    val toolName: String? = null,
    val status: String? = null,
    /** 事件附带的后端字段，例如工具进度或卡片引用；客户端不自行补造业务值。 */
    val payload: Map<String, String> = emptyMap()
) : Serializable

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
    val fields: Map<String, String> = emptyMap()
) : Serializable

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
    val navigation: AgentNavigationRequest? = null
)

data class ThisTaskAutoRequest(
    val sessionId: String,
    val addressId: Long,
    val maxAmount: BigDecimal,
    val expiresAt: Instant
)
