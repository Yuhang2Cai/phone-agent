package com.fandazi.app.feature.agent

import java.math.BigDecimal
import java.time.Instant
import java.io.Serializable

/** Only modes exposed by the public Agent Gateway.  CONFIRM is intentionally the default. */
enum class AgentMode { CONFIRM, THIS_TASK_AUTO }

enum class AgentStatus { COMPLETED, NEEDS_INPUT, AWAITING_CONFIRMATION, FAILED }

enum class AgentSheetMode { HIDDEN, HALF, FULL }

enum class AgentInputState { IDLE, RECORDING, RECOGNIZING }

data class ChatMessage(val text: String, val fromUser: Boolean) : Serializable

data class AgentEvent(
    val id: Long?,
    val type: String,
    val summary: String,
    val toolName: String? = null,
    val status: String? = null
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
    val error: String? = null
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
    val candidateCart: CandidateCart? = null,
    val confirmation: OrderConfirmation? = null,
    val authorization: AgentAuthorization = AgentAuthorization(),
    val error: AgentError? = null,
    val lastEventId: Long? = 0L,
    val isSending: Boolean = false,
    val inputState: AgentInputState = AgentInputState.IDLE,
    val createdOrderId: Long? = null
)

data class ThisTaskAutoRequest(
    val sessionId: String,
    val addressId: Long,
    val maxAmount: BigDecimal,
    val expiresAt: Instant
)
