// 首页模块：验证关键行为与边界条件。
package com.fandazi.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresentationTest {
    @Test
    fun loadingDoesNotExposeEmptyState() {
        val state = HomePresentationState.loading()

        assertEquals(HomeContentState.Loading, state.content)
        assertTrue(state.showSkeleton)
        assertTrue(!state.showEmptyMessage)
    }

    @Test
    fun noAddressKeepsSearchAndAssistantAvailable() {
        val state = HomePresentationState.content(address = null, merchants = emptyList())

        assertEquals(HomeContentState.NoAddress, state.content)
        assertTrue(state.searchEnabled)
        assertTrue(state.assistantEnabled)
        assertTrue(state.showAddressPrompt)
    }

    @Test
    fun emptyNearbyMerchantsUsesEmptyState() {
        val state = HomePresentationState.content(address = "上海·静安区", merchants = emptyList())

        assertEquals(HomeContentState.Empty, state.content)
        assertTrue(state.showEmptyMessage)
    }
}
