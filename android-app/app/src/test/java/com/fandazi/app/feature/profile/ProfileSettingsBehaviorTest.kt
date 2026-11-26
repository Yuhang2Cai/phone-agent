// 个人资料模块：验证关键行为与边界条件。
package com.fandazi.app.feature.profile

import com.fandazi.app.feature.ordering.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ProfileSettingsBehaviorTest {
    @Test
// 验证 `defaultAddressCannotBeDeleted` 覆盖场景的预期行为与边界。
    fun defaultAddressCannotBeDeleted() {
        val address = Address(1, "张先生", "13800000000", "上海市静安区南京西路 800 号", true)

        assertEquals(AddressAction.DeleteBlockedDefault, AddressPolicy.deleteAction(address))
    }

    @Test
// 验证 `invalidAddressCannotBeSelectedAsAutomationDefault` 覆盖场景的预期行为与边界。
    fun invalidAddressCannotBeSelectedAsAutomationDefault() {
        val state = AutomationSettingsState(defaultAddress = null, maxAmount = BigDecimal("30"))

        assertEquals(AutomationBlockReason.NoDefaultAddress, state.blockReason())
    }

    @Test
// 验证 `amountOverLimitBlocksAutomation` 覆盖场景的预期行为与边界。
    fun amountOverLimitBlocksAutomation() {
        val state = AutomationSettingsState(
            defaultAddress = Address(1, "张先生", "13800000000", "上海市静安区南京西路 800 号", true, true),
            maxAmount = BigDecimal("30"),
            proposedAmount = BigDecimal("31")
        )

        assertEquals(AutomationBlockReason.AmountOverLimit, state.blockReason())
        assertTrue(state.isBlocked)
    }

    @Test
// 验证 `validAutomationSettingsAreEnabled` 覆盖场景的预期行为与边界。
    fun validAutomationSettingsAreEnabled() {
        val state = AutomationSettingsState(
            defaultAddress = Address(1, "张先生", "13800000000", "上海市静安区南京西路 800 号", true, true),
            maxAmount = BigDecimal("30"),
            proposedAmount = BigDecimal("28")
        )

        assertEquals(null, state.blockReason())
        assertTrue(!state.isBlocked)
    }
}
