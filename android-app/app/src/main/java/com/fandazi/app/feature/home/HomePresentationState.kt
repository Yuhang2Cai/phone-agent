// 首页模块：定义该模块的数据与行为。
package com.fandazi.app.feature.home

import com.fandazi.app.feature.ordering.Merchant

enum class HomeContentState { Loading, Content, NoAddress, Empty, Error }

data class HomePresentationState(
    val content: HomeContentState,
    val showSkeleton: Boolean,
    val showEmptyMessage: Boolean,
    val searchEnabled: Boolean,
    val assistantEnabled: Boolean,
    val showAddressPrompt: Boolean
) {
    companion object {
        fun loading() = HomePresentationState(HomeContentState.Loading, true, false, false, false, false)

        fun content(address: String?, merchants: List<Merchant>) = HomePresentationState(
            content = when {
                address == null -> HomeContentState.NoAddress
                merchants.isEmpty() -> HomeContentState.Empty
                else -> HomeContentState.Content
            },
            showSkeleton = false,
            showEmptyMessage = address != null && merchants.isEmpty(),
            searchEnabled = true,
            assistantEnabled = true,
            showAddressPrompt = address == null
        )
    }
}
