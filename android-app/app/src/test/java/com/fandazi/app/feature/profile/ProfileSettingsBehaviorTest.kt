package com.fandazi.app.feature.profile

import com.fandazi.app.feature.ordering.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ProfileSettingsBehaviorTest {
    @Test
    fun defaultAddressCannotBeDeleted() {
        val address = Address(1, "张先生", "13800000000", "上海市静安区南京西路 800 号", true)

        assertEquals(AddressAction.DeleteBlockedDefault, AddressPolicy.deleteAction(address))
    }

    @Test
    fun invalidAddressCannotBeSelectedAsAutomationDefault() {
        val state = AutomationSettingsState(defaultAddress = null, maxAmount = BigDecimal("30"))

        assertEquals(AutomationBlockReason.NoDefaultAddress, state.blockReason())
    }

    @Test
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
