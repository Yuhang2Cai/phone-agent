// 搜索模块：定义该模块的数据与行为。
package com.fandazi.app.feature.search

import com.fandazi.app.feature.ordering.SearchStatus

enum class SearchFilter { COMPREHENSIVE, NEARBY, TOP_RATED, FAST_DELIVERY }

object SearchUiCopy {
// 处理 `status` 相关逻辑。
    fun status(status: SearchStatus): String = when (status) {
        SearchStatus.Idle -> "输入关键词，搜索附近好店"
        SearchStatus.Searching -> "正在为你寻找附近好店…"
        SearchStatus.Results -> "附近商家"
        SearchStatus.Empty -> "没有找到匹配的商家，换个关键词试试"
        SearchStatus.NetworkError -> "网络开小差了，点击重试"
        SearchStatus.AuthRequired -> "登录后即可查看个性化结果"
    }

// 处理 `filterLabel` 相关逻辑。
    fun filterLabel(filter: SearchFilter): String = when (filter) {
        SearchFilter.COMPREHENSIVE -> "综合"
        SearchFilter.NEARBY -> "距离近"
        SearchFilter.TOP_RATED -> "评分高"
        SearchFilter.FAST_DELIVERY -> "配送快"
    }
}
