// 个人资料模块：将业务状态映射为界面可展示内容。
package com.fandazi.app.feature.profile

import com.fandazi.app.feature.ordering.Address
import java.math.BigDecimal

enum class AddressAction { DeleteAllowed, DeleteBlockedDefault }

object AddressPolicy {
    fun deleteAction(address: Address): AddressAction =
        if (address.defaultAddress) AddressAction.DeleteBlockedDefault else AddressAction.DeleteAllowed
}

enum class AutomationBlockReason(val message: String) {
    NoDefaultAddress("请先设置有效默认地址"),
    AmountOverLimit("本次订单金额超过最高金额")
}

data class AutomationSettingsState(
    val defaultAddress: Address?,
    val maxAmount: BigDecimal,
    val proposedAmount: BigDecimal = BigDecimal.ZERO,
    val thisTaskEnabled: Boolean = false,
    val longTermEnabled: Boolean = false
) {
    val isBlocked: Boolean get() = blockReason() != null

    fun blockReason(): AutomationBlockReason? = when {
        defaultAddress?.defaultAddress != true || defaultAddress.valid.not() -> AutomationBlockReason.NoDefaultAddress
        proposedAmount > maxAmount -> AutomationBlockReason.AmountOverLimit
        else -> null
    }
}
