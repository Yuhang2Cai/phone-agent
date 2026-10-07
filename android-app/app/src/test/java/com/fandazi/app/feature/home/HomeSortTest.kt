package com.fandazi.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证首页排序规则，防止无定位时错误展示距离排序。 */
class HomeSortTest {
    @Test
    fun distanceSortIsDisabledWithoutLocation() {
        // 首页必须把定位权限状态传给排序策略，而不是展示伪造距离。
        assertEquals(false, HomeSortPolicy.canUseDistanceSort(null, null))
    }

    @Test
    fun distanceSortRequiresBothCoordinates() {
        // 只有经纬度同时存在时才允许调用后端距离排序。
        assertEquals(false, HomeSortPolicy.canUseDistanceSort(31.23, null))
        assertEquals(true, HomeSortPolicy.canUseDistanceSort(31.23, 121.47))
    }
}
