// 搜索模块：验证关键行为与边界条件。
package com.fandazi.app.feature.search

import com.fandazi.app.feature.ordering.SearchStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchUiPresentationTest {
    @Test
    fun statusCopyKeepsStatesDistinctAndActionable() {
        assertEquals("输入关键词，搜索附近好店", SearchUiCopy.status(SearchStatus.Idle))
        assertEquals("正在为你寻找附近好店…", SearchUiCopy.status(SearchStatus.Searching))
        assertEquals("没有找到匹配的商家，换个关键词试试", SearchUiCopy.status(SearchStatus.Empty))
        assertEquals("网络开小差了，点击重试", SearchUiCopy.status(SearchStatus.NetworkError))
        assertEquals("登录后即可查看个性化结果", SearchUiCopy.status(SearchStatus.AuthRequired))
    }

    @Test
    fun filterCopyUsesDesignLabels() {
        assertEquals("综合", SearchUiCopy.filterLabel(SearchFilter.COMPREHENSIVE))
        assertEquals("距离近", SearchUiCopy.filterLabel(SearchFilter.NEARBY))
        assertEquals("评分高", SearchUiCopy.filterLabel(SearchFilter.TOP_RATED))
        assertEquals("配送快", SearchUiCopy.filterLabel(SearchFilter.FAST_DELIVERY))
    }
}
